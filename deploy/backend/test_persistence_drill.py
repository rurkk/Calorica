"""Safety gates for the real lifecycle drill; no Docker or provider access."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from persistence_drill import DrillError, Stand, migration_compatibility


class PersistenceDrillSafetyTest(unittest.TestCase):
    def jar(self, root, name, content, extra=False):
        path = root / name
        with zipfile.ZipFile(path, 'w') as jar:
            prefix = 'BOOT-INF/classes/db/changelog/'
            master = 'databaseChangeLog:\n  - include:\n      file: changes/001.sql\n'
            jar.writestr(prefix + 'changes/001.sql', content)
            if extra:
                master += '  - include:\n      file: changes/002.sql\n'
                jar.writestr(prefix + 'changes/002.sql', 'CREATE TABLE extra(id int);')
            jar.writestr(prefix + 'db.changelog-master.yaml', master)
        return path

    def test_preflight_rejects_modified_applied_sql_without_starting_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            previous = self.jar(root, 'old.jar', 'original')
            changed = self.jar(root, 'new.jar', 'rewritten', extra=True)
            with self.assertRaisesRegex(DrillError, 'modified'):
                migration_compatibility(previous, changed)

    def test_additive_migrations_preserve_previous_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            previous = self.jar(root, 'old.jar', 'original')
            current = self.jar(root, 'new.jar', 'original', extra=True)
            old, new = migration_compatibility(previous, current)
            self.assertEqual(len(old), 1)
            self.assertEqual(len(new), 2)

    def test_cleanup_refuses_container_whose_ownership_changed(self):
        with tempfile.TemporaryDirectory() as directory:
            stand = Stand(Path(directory), 'java')
            stand.created = True
            with patch.object(stand, 'docker', return_value='another-run') as docker:
                with self.assertRaisesRegex(DrillError, 'not owned'):
                    stand.cleanup()
                self.assertEqual(docker.call_count, 1)
                self.assertEqual(docker.call_args.args[0], 'inspect')

    def test_cleanup_never_removes_a_container_it_did_not_create(self):
        with tempfile.TemporaryDirectory() as directory:
            stand = Stand(Path(directory), 'java')
            with patch.object(stand, 'docker') as docker:
                stand.cleanup()
                docker.assert_not_called()

    def test_public_health_probe_never_attempts_session_database_authentication(self):
        from urllib.error import HTTPError
        with tempfile.TemporaryDirectory() as directory:
            stand = Stand(Path(directory), 'java')
            stand.token = 'test-only-bearer'
            def offline_database(request, timeout):
                self.assertIsNone(request.get_header('Authorization'))
                from io import BytesIO
                raise HTTPError(request.full_url, 503, 'down', {}, BytesIO(b'{"status":"DOWN"}'))
            with patch('persistence_drill.urllib.request.urlopen', side_effect=offline_database):
                status, body = stand.http('GET', '/actuator/health/readiness', expected=None)
            self.assertEqual((status, body), (503, {'status': 'DOWN'}))
