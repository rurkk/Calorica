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


if __name__ == '__main__':
    unittest.main()
