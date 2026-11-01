#!/usr/bin/env python3
"""Create a backed-up permanent Android key; optionally upload GitHub settings.

Never run this in CI: CI only restores the existing key, and never generates one.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess


def setup(directory):
    directory = directory.expanduser().resolve()
    repository = Path(__file__).resolve().parents[2]
    if directory == repository or repository in directory.parents:
        raise ValueError('Signing backup must be outside the Git working copy')
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    directory.chmod(0o700)
    key = directory / 'calorica-release.jks'
    credentials = directory / 'credentials.json'
    if key.exists() != credentials.exists():
        raise ValueError('Incomplete key backup; recover it instead of generating a replacement')
    if not key.exists():
        password = secrets.token_urlsafe(36)
        data = dict(alias='calorica', store_password=password, key_password=password)
        # Save recovery material first; a failed generation is never silently retried.
        descriptor = os.open(credentials, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, 'w') as stream:
            json.dump(data, stream)
            stream.write('\n')
        environment = dict(os.environ, CALORICA_NEW_KEY_PASSWORD=password)
        subprocess.run(['keytool', '-genkeypair', '-noprompt', '-keystore', str(key),
                        '-storetype', 'PKCS12', '-storepass:env', 'CALORICA_NEW_KEY_PASSWORD',
                        '-keypass:env', 'CALORICA_NEW_KEY_PASSWORD', '-alias', data['alias'],
                        '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
                        '-dname', 'CN=Calorica Android Release'], env=environment, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        key.chmod(0o600)
    data = json.loads(credentials.read_text())
    environment = dict(os.environ, CALORICA_KEY_PASSWORD=data['store_password'])
    certificate = subprocess.check_output(['keytool', '-exportcert', '-keystore', str(key),
                                          '-storepass:env', 'CALORICA_KEY_PASSWORD',
                                          '-alias', data['alias']], env=environment, stderr=subprocess.PIPE)
    fingerprint = hashlib.sha256(certificate).hexdigest()
    (directory / 'certificate-sha256.txt').write_text(fingerprint + '\n')
    return key, data, fingerprint


def upload(repo, key, data, fingerprint):
    existing = json.loads(subprocess.check_output(
        ['gh', 'secret', 'list', '--repo', repo, '--json', 'name'], text=True))
    names = {item['name'] for item in existing}
    required = {'ANDROID_KEYSTORE_BASE64', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD'}
    if names & required:
        raise ValueError('Signing secrets already exist: refusing to rotate the permanent key')
    variables = json.loads(subprocess.check_output(
        ['gh', 'variable', 'list', '--repo', repo, '--json', 'name'], text=True))
    if any(item['name'] == 'ANDROID_SIGNING_CERT_SHA256' for item in variables):
        raise ValueError('Pinned certificate already exists: verify existing signing configuration')
    values = dict(ANDROID_KEYSTORE_BASE64=base64.b64encode(key.read_bytes()).decode(),
                  ANDROID_KEYSTORE_PASSWORD=data['store_password'],
                  ANDROID_KEY_ALIAS=data['alias'], ANDROID_KEY_PASSWORD=data['key_password'])
    for name, value in values.items():
        subprocess.run(['gh', 'secret', 'set', name, '--repo', repo], input=value,
                       text=True, check=True, stdout=subprocess.DEVNULL)
    subprocess.run(['gh', 'variable', 'set', 'ANDROID_SIGNING_CERT_SHA256', '--repo', repo,
                    '--body', fingerprint], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, required=True, help='Private backup outside the repository')
    parser.add_argument('--upload', action='store_true', help='Explicitly configure GitHub secrets and certificate pin')
    parser.add_argument('--repo', default='rurkk/Calorica')
    args = parser.parse_args()
    key, data, fingerprint = setup(args.directory)
    if args.upload:
        upload(args.repo, key, data, fingerprint)
    print(f'Key backup: {key.parent}\nCertificate SHA-256: {fingerprint}')
