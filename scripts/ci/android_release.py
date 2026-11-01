#!/usr/bin/env python3
"""Reserve Android versions, validate rollback and publish immutable APK assets.

All writes run under the workflow's calorica-android-release concurrency group.
Drafts are durable reservations; do not delete them to recycle version numbers.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

MARKER = re.compile(r'<!-- calorica-android:(\{[^\n]+\}) -->')
TAG = re.compile(r'android-v(\d+)\.(\d+)\.(\d+)')
SHA = re.compile(r'[a-f0-9]{40}')
PACKAGE = 'com.rurkk.calorica'


def command(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def api(path, payload=None):
    args = ['gh', 'api', path]
    if payload is not None:
        args += ['--method', 'POST', '--input', '-']
    return json.loads(command(*args, input=json.dumps(payload) if payload is not None else None))


def inventory(repo):
    pages = json.loads(command('gh', 'api', '--paginate', '--slurp',
                               f'repos/{repo}/releases?per_page=100'))
    return [release for page in pages for release in page]


def record(release):
    match = MARKER.search(release.get('body') or '')
    if not match:
        return None
    data = json.loads(match[1])
    if (data.get('schema') != 1 or not SHA.fullmatch(data.get('source_sha', ''))
            or release['tag_name'] != 'android-v' + data.get('version_name', '')
            or not isinstance(data.get('version_code'), int) or data['version_code'] < 1):
        raise ValueError('Invalid Android release reservation')
    return data


def compatibility(gradle):
    result = {}
    for key in ('Api', 'Data'):
        match = re.search(r'extra\["calorica' + key + r'Epoch"\]\s*=\s*(\d+)', gradle)
        if not match:
            raise ValueError('Source has no declared Android compatibility epochs')
        result[key.lower()] = int(match[1])
    return result


def baseline(gradle):
    code = int(re.search(r'versionCode\s*=.*?\?:\s*(\d+)', gradle)[1])
    name = re.search(r'versionName\s*=.*?\?:\s*"([0-9.]+)"', gradle)[1]
    return code, tuple(map(int, name.split('.')))


def select(releases, *, source_sha, gradle, run_id, mode, tag='', confirmed=False,
           reason='', tags=()):
    """Pure policy; network and durable reservation are handled by prepare()."""
    known = [(release, record(release)) for release in releases]
    known = [(release, data) for release, data in known if data]
    published = [(release, data) for release, data in known if not release['draft']]
    latest = max(published, key=lambda pair: pair[1]['version_code'], default=None)
    own = [(r, d) for r, d in known if d['run_id'] == str(run_id)]
    if mode == 'retry':
        own = [(r, d) for r, d in known if r['tag_name'] == tag]
        if not own:
            raise ValueError('Retry requires a reserved Android release tag')
    if own:
        release, data = own[0]
        if release['draft'] and latest and latest[1]['version_code'] >= data['version_code']:
            raise ValueError('Draft is superseded; start a new release instead')
        return dict(data, release_id=release['id'], release_url=release.get('html_url', ''),
                    published=not release['draft'])
    if mode == 'rollback':
        target = next(((r, d) for r, d in published if r['tag_name'] == tag), None)
        if not target or not latest or not confirmed or not reason.strip():
            raise ValueError('Rollback requires a published pipeline tag, confirmation and reason')
        if target[1]['version_code'] >= latest[1]['version_code']:
            raise ValueError('Rollback target must be older than the current Android release')
        if target[1]['compatibility'] != latest[1]['compatibility']:
            raise ValueError('Rollback crosses an API/local data compatibility boundary')
        source_sha = target[1]['source_sha']
        contract = target[1]['compatibility']
        if contract != compatibility(gradle):
            raise ValueError('Rollback is incompatible with current main')
    else:
        contract = compatibility(gradle)
    if not SHA.fullmatch(source_sha):
        raise ValueError('Release source must be an exact commit SHA')
    base_code, base_name = baseline(gradle)
    names = [tuple(map(int, TAG.fullmatch(t).groups())) for t in tags if TAG.fullmatch(t)]
    names += [tuple(map(int, d['version_name'].split('.'))) for _, d in known]
    previous = max(names, default=(0, 0, 0))
    version = max(base_name, (previous[0], previous[1], previous[2] + 1))
    code = max([base_code] + [d['version_code'] + 1 for _, d in known])
    if code > 2100000000:
        raise ValueError('Android versionCode limit exceeded')
    return dict(schema=1, source_sha=source_sha, version_code=code,
                version_name='.'.join(map(str, version)), run_id=str(run_id),
                compatibility=contract, mode=mode, rollback_tag=tag if mode == 'rollback' else '',
                reason=reason.strip(), published=False)


def notes(plan, repo):
    version = plan['version_name']
    run_url = f'https://github.com/{repo}/actions/runs/{plan["run_id"]}'
    metadata = {k: v for k, v in plan.items() if k not in ('release_id', 'release_url', 'published')}
    # Reason is free text: do not allow a newline to corrupt the metadata marker.
    marker = json.dumps(metadata, ensure_ascii=True, separators=(',', ':'))
    return (f'Calorica {version} · versionCode {plan["version_code"]}\n\n'
            f'Исходники: https://github.com/{repo}/commit/{plan["source_sha"]}\n'
            f'Сборка и логи: {run_url}\n\n'
            f'Режим: {plan["mode"]}; исходный выпуск отката: {plan["rollback_tag"] or "—"}.\n'
            'Подписанный release APK. Продуктовые функции семестрового MVP ещё в разработке.\n'
            'Проверяйте совместимость API и данных перед установкой.\n\n'
            f'<!-- calorica-android:{marker} -->')


def outputs(plan):
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            for key in ('source_sha', 'version_code', 'version_name', 'release_id', 'published'):
                print(f'{key}={str(plan[key]).lower() if isinstance(plan[key], bool) else plan[key]}', file=stream)


def prepare(args):
    repo = os.environ['GITHUB_REPOSITORY']
    releases = inventory(repo)
    gradle = Path('app/build.gradle.kts').read_text()
    tags = command('git', 'tag', '--list', 'android-v*').splitlines()
    plan = select(releases, source_sha=os.environ['GITHUB_SHA'], gradle=gradle,
                  run_id=os.environ['GITHUB_RUN_ID'], mode=args.mode, tag=args.tag,
                  confirmed=args.confirmed, reason=args.reason, tags=tags)
    if 'release_id' not in plan:
        release = api(f'repos/{repo}/releases', dict(
            tag_name='android-v' + plan['version_name'], target_commitish=plan['source_sha'],
            name='Calorica ' + plan['version_name'], body=notes(plan, repo),
            draft=True, prerelease=False))
        plan['release_id'] = release['id']
        plan['release_url'] = release.get('html_url', '')
    Path('_release').mkdir(exist_ok=True)
    Path('_release/plan.json').write_text(json.dumps(plan, indent=2) + '\n')
    outputs(plan)
    if plan['published']:
        release = api(f'repos/{repo}/releases/{plan["release_id"]}')
        expected = {'calorica.apk', 'release.json', 'SHA256SUMS'}
        if not expected <= {a['name'] for a in release['assets']}:
            raise ValueError('Published release is incomplete; refusing to replace its assets')


def verify_apk(apk, plan, sdk, fingerprint):
    installed = [p for p in (Path(sdk) / 'build-tools').iterdir()
                 if p.is_dir() and re.fullmatch(r'\d+\.\d+\.\d+', p.name)]
    if not installed:
        raise ValueError('Install a stable Android SDK Build-Tools version')
    tools = max(installed, key=lambda p: tuple(map(int, p.name.split('.'))))
    result = command(str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk))
    # SDKs label signers by number, min/max SDK range, or signature scheme (SDK 37).
    # Every reported signing certificate must match; a source stamp is not a signer.
    certs = re.findall(
        r'(?m)^(?:Signer [^\r\n]+|V[1-4](?:\.\d+)? Signer[^:\r\n]*:) '
        r'certificate SHA-256 digest: ([a-fA-F0-9]{64})[ \t]*$', result)
    expected = fingerprint.replace(':', '').lower()
    if not re.fullmatch('[a-f0-9]{64}', expected) or {c.lower() for c in certs} != {expected}:
        raise ValueError(f'APK signer differs from the pinned permanent certificate: '
                         f'expected={expected}, actual={certs}, build_tools={tools.name}, '
                         f'certificate_lines={[line for line in result.splitlines() if "certificate" in line.lower()]}')
    badging = command(str(tools / 'aapt'), 'dump', 'badging', str(apk))
    package = badging.splitlines()[0]
    if (f"name='{PACKAGE}'" not in package
            or f"versionCode='{plan['version_code']}'" not in package
            or f"versionName='{plan['version_name']}'" not in package
            or 'application-debuggable' in badging):
        raise ValueError('APK identity/version differs from release reservation')
    return expected


def publish(args):
    plan = json.loads(Path('_release/plan.json').read_text())
    repo = os.environ['GITHUB_REPOSITORY']
    release = api(f'repos/{repo}/releases/{plan["release_id"]}')
    if not release['draft'] or record(release)['source_sha'] != plan['source_sha']:
        raise ValueError('Reservation changed or is already published')
    newer = [record(r) for r in inventory(repo) if not r['draft']]
    if any(d and d['version_code'] >= plan['version_code'] for d in newer):
        raise ValueError('Reservation is superseded')
    if command('git', '-C', 'source', 'rev-parse', 'HEAD') != plan['source_sha']:
        raise ValueError('Checked out source differs from reservation')
    tag = 'android-v' + plan['version_name']
    refs = api(f'repos/{repo}/git/matching-refs/tags/{tag}')
    exact_refs = [ref for ref in refs if ref['ref'] == f'refs/tags/{tag}']
    if any(ref['object']['type'] != 'commit' or ref['object']['sha'] != plan['source_sha']
           for ref in exact_refs):
        raise ValueError('Release tag already points to another source')
    source_gradle = Path('source/app/build.gradle.kts').read_text()
    if compatibility(source_gradle) != plan['compatibility']:
        raise ValueError('Source compatibility differs from reservation')
    apk = Path('source/app/build/outputs/apk/release/app-release.apk')
    fingerprint = verify_apk(apk, plan, os.environ['ANDROID_HOME'], os.environ['ANDROID_SIGNING_CERT_SHA256'])
    directory = Path('_release/assets')
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'calorica.apk').write_bytes(apk.read_bytes())
    manifest = {k: v for k, v in plan.items() if k not in ('release_id', 'release_url', 'published')}
    manifest.update(certificate_sha256=fingerprint, apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                    build_run_id=os.environ['GITHUB_RUN_ID'])
    (directory / 'release.json').write_text(json.dumps(manifest, indent=2) + '\n')
    checksums = ''.join(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n'
                        for path in sorted(directory.iterdir()) if path.name != 'SHA256SUMS')
    (directory / 'SHA256SUMS').write_text(checksums)
    # Only drafts can be repaired on retry. Published assets are never overwritten.
    command('gh', 'release', 'upload', tag, *map(str, sorted(directory.iterdir())), '--clobber', '--repo', repo)
    command('gh', 'release', 'edit', tag, '--draft=false', '--latest', '--repo', repo)
    plan['published'] = True
    plan['release_url'] = f'https://github.com/{repo}/releases/tag/{tag}'
    Path('_release/plan.json').write_text(json.dumps(plan, indent=2) + '\n')


def summary(args):
    repo = os.environ['GITHUB_REPOSITORY']
    url = f'https://github.com/{repo}'
    run = f'{url}/actions/runs/{os.environ["GITHUB_RUN_ID"]}'
    lines = [f'Android release: **{args.status}**', f'[Workflow и логи]({run})',
             f'Workflow SHA: [{os.environ["GITHUB_SHA"]}]({url}/commit/{os.environ["GITHUB_SHA"]})']
    plan_file = Path('_release/plan.json')
    if plan_file.exists():
        plan = json.loads(plan_file.read_text())
        tag = 'android-v' + plan['version_name']
        release = plan.get('release_url') or f'{url}/releases'
        lines += [f'Версия: {plan["version_name"]} ({plan["version_code"]}); режим: {plan["mode"]}',
                  f'Исходный SHA: [{plan["source_sha"]}]({url}/commit/{plan["source_sha"]})',
                  f'[Release / резерв версии]({release})']
        if plan['published']:
            lines += [f'[Скачать APK]({url}/releases/download/{tag}/calorica.apk)',
                      f'[Метаданные и SHA-256]({url}/releases/download/{tag}/release.json)']
        else:
            lines += ['Публикация APK не подтверждена. Проверьте Release; затем повторите запуск или выберите retry и тег резерва.']
    for label, variable, fallback in (
        ('Стенд / readiness', 'CALORICA_DEPLOY_URL', 'https://calorica-backend.onrender.com/actuator/health/readiness'),
        ('Backend CI / деплой', 'CALORICA_DEPLOY_LOGS_URL', f'{url}/actions/workflows/backend.yml'),
        ('Логи сервера', 'CALORICA_LOGS_URL', 'https://telemetry.betterstack.com/team/t627028/tail?s=l2819108'),
    ):
        lines += [f'[{label}]({os.environ.get(variable) or fallback})']
    lines += ['Ссылки стенда информационные: этот workflow не развёртывает и не проверяет backend.']
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
        stream.write('\n\n'.join(lines) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    start = sub.add_parser('prepare')
    start.add_argument('--mode', choices=('release', 'retry', 'rollback'), default='release')
    start.add_argument('--tag', default='')
    start.add_argument('--confirmed', action='store_true')
    start.add_argument('--reason', default='')
    sub.add_parser('publish')
    report = sub.add_parser('summary')
    report.add_argument('--status', required=True)
    arguments = parser.parse_args()
    {'prepare': prepare, 'publish': publish, 'summary': summary}[arguments.command](arguments)
