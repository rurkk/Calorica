#!/usr/bin/env python3
"""Manual status, personal read-only SQL, and isolated local backend debugging."""
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


def readonly_environment(values, registry):
    if values.get('PGHOST') != registry['shared_db_host']:
        raise AccessError('db commands target only the registered shared host.')
    if values.get('PGDATABASE') != registry['database']:
        raise AccessError('Unexpected shared database.')
    if not re.fullmatch(r'calorica_ro_[a-z][a-z0-9_]{0,30}', values.get('PGUSER', '')):
        raise AccessError('Use your personal calorica_ro_* login, not the owner.')
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


def query(values, registry, sql, runner=subprocess.run, binary=None):
    env = readonly_environment(values, registry)
    if not sql.strip():
        raise AccessError('SQL is empty.')
    # psql also interprets backslash commands, including shell/file operations.
    # This small helper accepts SQL only, including for input read from a file.
    if '\\' in sql:
        raise AccessError('Backslashes and psql commands are not supported; use SQL only.')
    # The DB role has SELECT grants only. A READ ONLY transaction is a second limit.
    result = runner([binary or psql_binary(), '-X', '--no-password', '-v', 'ON_ERROR_STOP=1',
                     '-P', 'pager=off'], input='BEGIN READ ONLY;\n' + sql + '\nROLLBACK;\n',
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
    db = commands.add_parser('db', help='Query shared DB with your personal read-only login.')
    sql_input = db.add_mutually_exclusive_group(required=True)
    sql_input.add_argument('--sql')
    sql_input.add_argument('--file', type=Path)
    commands.add_parser('db-tables', help='List application tables without reading rows.')
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
        if args.command in ('db', 'db-tables'):
            if args.command == 'db-tables':
                sql = "SELECT schemaname, tablename FROM pg_tables WHERE schemaname = 'calorica' ORDER BY tablename;"
            else:
                sql = args.sql if args.sql is not None else args.file.read_text(encoding='utf-8')
            return query(values, registry, sql)
        env = development_environment(values, registry)
        wrapper = ROOT / 'backend' / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        command = [str(wrapper), 'bootRun'] + (['--debug-jvm'] if args.debug else [])
        return subprocess.run(command, cwd=ROOT / 'backend', env=env).returncode
    except (AccessError, OSError, subprocess.TimeoutExpired) as error:
        print('Access setup error: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
