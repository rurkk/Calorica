#!/usr/bin/env python3
"""Calorica status, shared administrative API/SQL, MCP credentials, and local debugging."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import stat
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / 'deploy/access/infrastructure.json'
PROVIDERS = {
    'neon': ('https://console.neon.tech/api/v2', 'NEON_API_KEY'),
    'render': ('https://api.render.com/v1', 'RENDER_API_KEY'),
    'betterstack': ('https://telemetry.betterstack.com/api/v2', 'BETTERSTACK_API_KEY'),
}
MCP_SERVERS = {
    'neon': ('CaloricaNeon', 'https://mcp.neon.tech/mcp?projectId=long-king-45707171'),
    'render': ('CaloricaRender', 'https://mcp.render.com/mcp'),
    'betterstack': ('CaloricaLogs', 'https://mcp.betterstack.com'),
}


class AccessError(Exception):
    pass


def load_env(path):
    path = Path(path)
    if not path.is_file():
        raise AccessError('Personal profile missing: ask the owner for .env.infra.')
    if os.name != 'nt' and stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise AccessError('Personal profile must be private: chmod 600 ' + str(path))
    values = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, separator, raw = line.partition('=')
        key = key.strip()
        if not separator or not re.fullmatch(r'[A-Z_][A-Z0-9_]*', key) or key in values:
            raise AccessError('Invalid or duplicate profile key; no values were printed.')
        try:
            parts = shlex.split(raw, comments=True, posix=True)
        except ValueError:
            raise AccessError('Invalid profile quoting; no values were printed.') from None
        if len(parts) > 1:
            raise AccessError('Quote profile values containing spaces.')
        values[key] = parts[0] if parts else ''
    return values


def redact(text, values):
    for key, value in values.items():
        if value and any(marker in key for marker in ('PASSWORD', 'TOKEN', 'API_KEY')):
            text = text.replace(value, '[secret hidden]')
    return text


def shared_admin(values):
    return values.get('ACCESS_MODE') == 'shared_admin'


def mcp_headers(values, provider):
    if provider not in PROVIDERS or not shared_admin(values):
        raise AccessError('Use the shared admin profile and a registered provider.')
    token = values.get(PROVIDERS[provider][1], '')
    if not token or any(character.isspace() for character in token):
        raise AccessError('Provider API key missing or invalid; no values were printed.')
    return {'Authorization': 'Bearer ' + token}


def codex_command(arguments):
    command = ['codex', '--cd', str(ROOT)]
    for provider, (name, url) in MCP_SERVERS.items():
        command.extend(['-c', 'mcp_servers.' + name + '.url=' + json.dumps(url),
                        '-c', 'mcp_servers.' + name + '.bearer_token_env_var=' +
                        json.dumps(PROVIDERS[provider][1])])
    return command + arguments


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward an administrative Authorization header to another URL.
        return None


def provider_request(values, provider, path, method='GET', payload=None, opener=None):
    headers = mcp_headers(values, provider)
    parsed = urllib.parse.urlsplit(path)
    if not path.startswith('/') or path.startswith('//') or parsed.scheme or parsed.netloc:
        raise AccessError('API path must be relative to the registered provider, starting with /.')
    if method not in ('GET', 'POST', 'PATCH', 'PUT', 'DELETE') or (method == 'GET' and payload is not None):
        raise AccessError('Unsupported API method/body combination.')
    headers.update(Accept='application/json', **{'Content-Type': 'application/json'})
    request = urllib.request.Request(PROVIDERS[provider][0] + path,
                                     data=json.dumps(payload).encode() if payload is not None else None,
                                     headers=headers, method=method)
    open_request = opener or urllib.request.build_opener(NoRedirect()).open
    try:
        response = open_request(request, timeout=45)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read(4 * 1024 * 1024 + 1)
        if len(raw) > 4 * 1024 * 1024:
            raise AccessError('API response exceeds 4 MiB; use pagination.')
        try:
            data = json.loads(raw) if raw else None
        except ValueError:
            data = {'message': 'Non-JSON response omitted.'}
        return {'http': response.status, 'data': data}


def safe_payload(payload, values):
    def sensitive(key):
        key = re.sub(r'[^a-z0-9]', '', key.lower())
        return any(marker in key for marker in ('password', 'secret', 'token', 'apikey',
                   'privatekey', 'connectionstring', 'connectionuri', 'connectionurl', 'deployhook'))
    if isinstance(payload, dict):
        secret_env_value = sensitive(str(payload.get('key', payload.get('name', ''))))
        return {key: '[secret hidden]' if sensitive(key) or key == 'key' and 'value' not in payload
                or key == 'value' and secret_env_value else safe_payload(value, values)
                for key, value in payload.items()}
    if isinstance(payload, list):
        return [safe_payload(value, values) for value in payload]
    if isinstance(payload, str):
        text = redact(payload, values)
        return re.sub(r'(postgres(?:ql)?://)[^/@\s]+:[^@\s]+@', r'\1[credentials hidden]@', text)
    return payload


def readonly_environment(values, registry):
    if values.get('PGHOST') != registry['shared_db_host']:
        raise AccessError('db commands target only the registered shared host.')
    if values.get('PGDATABASE') != registry['database']:
        raise AccessError('Unexpected shared database.')
    personal_reader = re.fullmatch(r'calorica_ro_[a-z][a-z0-9_]{0,30}', values.get('PGUSER', ''))
    if not personal_reader and not (shared_admin(values) and values.get('PGUSER') == 'neondb_owner'):
        raise AccessError('Use a personal reader or the explicit shared admin profile.')
    if not values.get('PGPASSWORD'):
        raise AccessError('PGPASSWORD is missing from your personal profile.')
    env = {key: value for key, value in os.environ.items() if not key.startswith('PG')}
    env.update({key: values[key] for key in ('PGHOST', 'PGDATABASE', 'PGUSER', 'PGPASSWORD')})
    env.update(PGPORT='5432', PGSSLMODE='verify-full', PGSSLROOTCERT='system',
               PGCHANNELBINDING='require', PGCONNECT_TIMEOUT='15',
               PGAPPNAME='calorica-team-readonly', PGCLIENTENCODING='UTF8',
               PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=5000 '
                         '-c lock_timeout=2000 -c idle_in_transaction_session_timeout=10000')
    return env


def psql_binary():
    candidates = [shutil.which('psql'), '/opt/homebrew/opt/libpq/bin/psql',
                  '/opt/homebrew/opt/postgresql@17/bin/psql']
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise AccessError('Install PostgreSQL client psql 17 or newer and add it to PATH.')


def query(values, registry, sql, runner=subprocess.run, binary=None, write=False):
    env = readonly_environment(values, registry)
    if write:
        if not shared_admin(values) or values.get('PGUSER') != 'neondb_owner':
            raise AccessError('db-admin requires the shared administrative SQL profile.')
        env['PGOPTIONS'] = ('-c default_transaction_read_only=off -c statement_timeout=30000 '
                            '-c lock_timeout=5000 -c idle_in_transaction_session_timeout=10000')
        env['PGAPPNAME'] = 'calorica-team-admin'
    if not sql.strip():
        raise AccessError('SQL is empty.')
    # psql also interprets backslash commands, including shell/file operations.
    # This small helper accepts SQL only, including for input read from a file.
    if '\\' in sql:
        raise AccessError('Backslashes and psql commands are not supported; use SQL only.')
    # Personal roles have SELECT grants only. Shared admins can explicitly commit.
    result = runner([binary or psql_binary(), '-X', '--no-password', '-v', 'ON_ERROR_STOP=1',
                     '-P', 'pager=off'], input=('BEGIN;\n' if write else 'BEGIN READ ONLY;\n') + sql +
                     ('\nCOMMIT;\n' if write else '\nROLLBACK;\n'),
                    env=env, capture_output=True, text=True, timeout=30)
    sys.stdout.write(redact(result.stdout, values))
    sys.stderr.write(redact(result.stderr, values))
    return result.returncode


def development_environment(values, registry):
    url = values.get('DEV_DB_URL', '')
    if not url.startswith('jdbc:postgresql://'):
        raise AccessError('DEV_DB_URL must be a JDBC PostgreSQL URL.')
    parsed = urllib.parse.urlsplit(url.removeprefix('jdbc:'))
    if (not parsed.hostname or not parsed.hostname.endswith('.neon.tech')
            or parsed.hostname == registry['shared_db_host'] or '-pooler.' in parsed.hostname):
        raise AccessError('Local backend requires your separate direct dev endpoint, not main.')
    params = urllib.parse.parse_qs(parsed.query)
    if params.get('sslmode') != ['require'] or params.get('channelBinding') != ['require']:
        raise AccessError('Keep TLS and channel binding in the development JDBC URL.')
    if not values.get('DEV_BRANCH', '').startswith('dev-'):
        raise AccessError('DEV_BRANCH must identify your isolated dev branch.')
    if not values.get('DEV_DB_USERNAME') or not values.get('DEV_DB_PASSWORD'):
        raise AccessError('Development credentials are missing.')
    env = dict(os.environ)
    env.update(DB_URL=url, DB_USERNAME=values['DEV_DB_USERNAME'],
               DB_PASSWORD=values['DEV_DB_PASSWORD'], DB_POOL_SIZE='2',
               SPRING_PROFILES_ACTIVE='prod', SERVER_PORT='8080')
    return env


def status(registry, timeout):
    results = {}
    for name in ('liveness', 'readiness'):
        url = registry['api_base_url'] + '/actuator/health/' + name
        try:
            with urllib.request.urlopen(url, timeout=timeout) as response:
                payload = json.loads(response.read(65536))
                results[name] = {'http': response.status, 'status': payload.get('status')}
        except (OSError, ValueError, urllib.error.URLError):
            results[name] = {'status': 'UNAVAILABLE_OR_WAKING', 'hint':
                             'Render Free may be waking after idle; retry manually.'}
    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0 if all(item.get('http') == 200 and item.get('status') == 'UP'
                    for item in results.values()) else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / '.env.infra')
    commands = parser.add_subparsers(dest='command', required=True)
    health = commands.add_parser('status', help='One manual public health check; no credentials.')
    health.add_argument('--timeout', type=float, default=90)
    for name, description in (('db', 'Query shared DB in a read-only transaction.'),
                              ('db-admin', 'Execute and COMMIT SQL using the shared admin profile.')):
        db = commands.add_parser(name, help=description)
        sql_input = db.add_mutually_exclusive_group(required=True)
        sql_input.add_argument('--sql')
        sql_input.add_argument('--file', type=Path)
    commands.add_parser('db-tables', help='List application tables without reading rows.')
    api = commands.add_parser('api', help='Call an administrative provider API; default GET.')
    api.add_argument('provider', choices=PROVIDERS)
    api.add_argument('path', help='Path after the provider API base, beginning with /.')
    api.add_argument('--method', choices=('GET', 'POST', 'PATCH', 'PUT', 'DELETE'), default='GET')
    api.add_argument('--data-file', type=Path, help='JSON request body; never put secrets in argv.')
    api.add_argument('--output-file', type=Path, help='Save an unredacted response to a NEW private file.')
    headers = commands.add_parser('mcp-headers', help='Internal Codex credential helper; do not run in chat.')
    headers.add_argument('provider', choices=PROVIDERS)
    run = commands.add_parser('run', help='Run a CLI with the shared profile in its environment.')
    run.add_argument('arguments', nargs=argparse.REMAINDER)
    agent = commands.add_parser('codex', help='Start Codex with all three MCP servers and shared credentials.')
    agent.add_argument('arguments', nargs=argparse.REMAINDER)
    dev = commands.add_parser('dev-run', help='Run Spring Boot locally against your dev DB.')
    dev.add_argument('--debug', action='store_true', help='JDWP on local port 5005.')
    args = parser.parse_args(argv)
    try:
        registry = json.loads(REGISTRY.read_text())
        if args.command == 'status':
            if not 1 <= args.timeout <= 120:
                raise AccessError('Status timeout must be between 1 and 120 seconds.')
            return status(registry, args.timeout)
        values = load_env(args.env_file)
        if args.command == 'mcp-headers':
            # Machine-to-machine credential channel; Codex consumes this stdout.
            print(json.dumps(mcp_headers(values, args.provider)))
            return 0
        if args.command == 'api':
            if args.output_file and args.output_file.exists():
                raise AccessError('API output file must be new; existing files are never overwritten.')
            payload = json.loads(args.data_file.read_text()) if args.data_file else None
            result = provider_request(values, args.provider, args.path, args.method, payload)
            if args.output_file:
                descriptor = os.open(args.output_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, 'w') as file:
                    json.dump(result, file, ensure_ascii=False, indent=2)
                print(json.dumps({'http': result['http'], 'saved_privately': True}))
            else:
                print(json.dumps(safe_payload(result, values), ensure_ascii=False, indent=2))
            return 0 if 200 <= result['http'] < 300 else 1
        if args.command in ('run', 'codex'):
            if not shared_admin(values):
                raise AccessError('run requires the shared admin profile.')
            command = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
            if args.command == 'codex':
                for provider in PROVIDERS:
                    mcp_headers(values, provider)
                command = codex_command(command)
            elif not command:
                raise AccessError('Specify a CLI command after run --.')
            return subprocess.run(command, cwd=ROOT, env=dict(os.environ, **values)).returncode
        if args.command in ('db', 'db-admin', 'db-tables'):
            if args.command == 'db-tables':
                sql = "SELECT schemaname, tablename FROM pg_tables WHERE schemaname = 'calorica' ORDER BY tablename;"
            else:
                sql = args.sql if args.sql is not None else args.file.read_text(encoding='utf-8')
            return query(values, registry, sql, write=args.command == 'db-admin')
        env = development_environment(values, registry)
        wrapper = ROOT / 'backend' / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        command = [str(wrapper), 'bootRun'] + (['--debug-jvm'] if args.debug else [])
        return subprocess.run(command, cwd=ROOT / 'backend', env=env).returncode
    except (AccessError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print('Access setup error: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
