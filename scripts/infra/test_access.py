import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('access', Path(__file__).with_name('access.py'))
access = importlib.util.module_from_spec(spec)
spec.loader.exec_module(access)
REGISTRY = json.loads(access.REGISTRY.read_text())


class InfrastructureAccessTest(unittest.TestCase):
    def profile(self):
        return {'PGHOST': REGISTRY['shared_db_host'], 'PGDATABASE': 'neondb',
                'PGUSER': 'calorica_ro_test', 'PGPASSWORD': 'private-password-example'}

    def test_loading_a_profile_does_not_execute_shell_expressions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'profile.env'
            path.write_text("PGPASSWORD='$(touch MUST_NOT_EXIST)'\n")
            path.chmod(0o600)
            self.assertEqual(access.load_env(path)['PGPASSWORD'], '$(touch MUST_NOT_EXIST)')
            self.assertFalse((Path(directory) / 'MUST_NOT_EXIST').exists())

    @unittest.skipIf(os.name == 'nt', 'POSIX file modes')
    def test_loading_rejects_a_profile_readable_by_other_users(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'profile.env'
            path.write_text('PGPASSWORD=private\n')
            path.chmod(0o644)
            with self.assertRaises(access.AccessError):
                access.load_env(path)

    def test_shared_queries_reject_owner_credentials(self):
        profile = dict(self.profile(), PGUSER='neondb_owner')
        with self.assertRaises(access.AccessError):
            access.readonly_environment(profile, REGISTRY)

    def test_query_keeps_the_password_out_of_arguments_and_redacts_errors(self):
        profile = self.profile()
        def runner(command, **kwargs):
            self.assertNotIn(profile['PGPASSWORD'], ' '.join(command))
            self.assertEqual(kwargs['env']['PGPASSWORD'], profile['PGPASSWORD'])
            self.assertEqual(kwargs['env']['PGSSLMODE'], 'verify-full')
            self.assertTrue(kwargs['input'].startswith('BEGIN READ ONLY;'))
            self.assertIn('statement_timeout=5000', kwargs['env']['PGOPTIONS'])
            return SimpleNamespace(returncode=1, stdout='', stderr=profile['PGPASSWORD'])
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(access.query(profile, REGISTRY, 'SELECT 1;', runner, 'psql-test'), 1)
        self.assertNotIn(profile['PGPASSWORD'], errors.getvalue())
        self.assertIn('[secret hidden]', errors.getvalue())

    def test_query_rejects_psql_shell_commands_before_starting_a_process(self):
        def runner(*args, **kwargs):
            self.fail('A SQL-only helper must not send shell commands to psql.')
        for sql in ('\\! env', 'SELECT 1; \\! env'):
            with self.subTest(sql=sql), self.assertRaises(access.AccessError):
                access.query(self.profile(), REGISTRY, sql, runner, 'psql-test')

    def test_local_backend_rejects_the_shared_database_even_with_developer_credentials(self):
        values = {'DEV_BRANCH': 'dev-test', 'DEV_DB_USERNAME': 'neondb_owner',
                  'DEV_DB_PASSWORD': 'private',
                  'DEV_DB_URL': 'jdbc:postgresql://' + REGISTRY['shared_db_host'] +
                                '/neondb?sslmode=require&channelBinding=require'}
        with self.assertRaises(access.AccessError):
            access.development_environment(values, REGISTRY)

    def test_local_backend_requires_tls_and_a_separate_development_host(self):
        values = {'DEV_BRANCH': 'dev-test', 'DEV_DB_USERNAME': 'neondb_owner',
                  'DEV_DB_PASSWORD': 'private',
                  'DEV_DB_URL': 'jdbc:postgresql://ep-test.neon.tech/neondb?sslmode=disable'}
        with self.assertRaises(access.AccessError):
            access.development_environment(values, REGISTRY)
        values['DEV_DB_URL'] = values['DEV_DB_URL'].replace('sslmode=disable', 'sslmode=require&channelBinding=require')
        env = access.development_environment(values, REGISTRY)
        self.assertEqual(env['DB_PASSWORD'], 'private')
        self.assertEqual(env['DB_POOL_SIZE'], '2')

    def shared_profile(self):
        return dict(self.profile(), ACCESS_MODE='shared_admin', PGUSER='neondb_owner',
                    NEON_API_KEY='private-neon-key', RENDER_API_KEY='private-render-key',
                    BETTERSTACK_API_KEY='private-logs-key')

    def test_shared_admin_keeps_default_queries_read_only(self):
        env = access.readonly_environment(self.shared_profile(), REGISTRY)
        self.assertIn('default_transaction_read_only=on', env['PGOPTIONS'])

    def test_write_queries_require_the_explicit_shared_admin_profile(self):
        with self.assertRaises(access.AccessError):
            access.query(self.profile(), REGISTRY, 'SELECT 1;', write=True)

    def test_explicit_admin_queries_commit_and_keep_the_password_out_of_arguments(self):
        profile = self.shared_profile()
        def runner(command, **kwargs):
            self.assertNotIn(profile['PGPASSWORD'], ' '.join(command))
            self.assertTrue(kwargs['input'].startswith('BEGIN;'))
            self.assertTrue(kwargs['input'].endswith('COMMIT;\n'))
            self.assertIn('default_transaction_read_only=off', kwargs['env']['PGOPTIONS'])
            return SimpleNamespace(returncode=0, stdout='', stderr='')
        access.query(profile, REGISTRY, 'SELECT 1;', runner, 'psql-test', write=True)

    def test_mcp_headers_use_only_the_selected_provider_key(self):
        profile = self.shared_profile()
        self.assertEqual(access.mcp_headers(profile, 'render'),
                         {'Authorization': 'Bearer private-render-key'})
        with self.assertRaises(access.AccessError):
            access.mcp_headers(self.profile(), 'render')

    def test_api_rejects_foreign_urls_before_sending_credentials(self):
        for path in ('https://outside.example/', '//outside.example/'):
            with self.subTest(path=path), self.assertRaises(access.AccessError):
                access.provider_request(self.shared_profile(), 'render', path)

    def test_api_uses_the_registered_origin_and_disables_redirects(self):
        def opener(request, **kwargs):
            self.assertEqual(request.full_url, 'https://api.render.com/v1/services')
            self.assertEqual(request.get_header('Authorization'), 'Bearer private-render-key')
            class Response:
                status = 200
                def __enter__(self): return self
                def __exit__(self, *args): pass
                def read(self, size): return b'{"ok":true}'
            return Response()
        result = access.provider_request(self.shared_profile(), 'render', '/services', opener=opener)
        self.assertEqual(result, {'http': 200, 'data': {'ok': True}})
        self.assertIsNone(access.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://outside.example/'))

    def test_api_output_hides_generated_secrets_and_secret_environment_values(self):
        payload = {'role': {'password': 'new-password'}, 'key': 'new-api-key',
                   'envVars': [{'key': 'DB_PASSWORD', 'value': 'production-password'},
                               {'key': 'PORT', 'value': '8080'}]}
        rendered = json.dumps(access.safe_payload(payload, self.shared_profile()))
        for secret in ('new-password', 'new-api-key', 'production-password'):
            self.assertNotIn(secret, rendered)
        self.assertIn('8080', rendered)

    def test_agent_launcher_passes_only_key_variable_names_in_arguments(self):
        command = access.codex_command(['mcp', 'get', 'CaloricaRender'])
        rendered = ' '.join(command)
        for provider in ('CaloricaNeon', 'CaloricaRender', 'CaloricaLogs'):
            self.assertIn('mcp_servers.' + provider + '.url=', rendered)
        self.assertIn('bearer_token_env_var="RENDER_API_KEY"', rendered)
        for key, secret in self.shared_profile().items():
            if 'PASSWORD' in key or 'API_KEY' in key:
                self.assertNotIn(secret, rendered)
        self.assertEqual(command[-3:], ['mcp', 'get', 'CaloricaRender'])


if __name__ == '__main__':
    unittest.main()
