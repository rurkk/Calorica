import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import android_release as release

GRADLE = '''
extra["caloricaApiEpoch"] = 1
extra["caloricaDataEpoch"] = 1
versionCode = providers.environmentVariable("CALORICA_VERSION_CODE").orNull?.toInt() ?: 3
versionName = providers.environmentVariable("CALORICA_VERSION_NAME").orNull ?: "0.1.2"
'''
SHA = 'a' * 40


def reservation(code=3, version='0.1.2', draft=False, run='100', contract=None):
    data = dict(schema=1, source_sha=SHA, version_code=code, version_name=version,
                run_id=run, compatibility=contract or dict(api=1, data=1),
                mode='release', rollback_tag='', reason='', published=False)
    return dict(id=code, tag_name='android-v' + version, draft=draft,
                body=release.notes(data, 'rurkk/Calorica'))


class AndroidReleaseTest(unittest.TestCase):
    def choose(self, releases=(), **kwargs):
        options = dict(source_sha='b' * 40, gradle=GRADLE, run_id='200', mode='release')
        options.update(kwargs)
        return release.select(releases, **options)

    def test_first_release_uses_source_floor_and_skips_existing_tags(self):
        plan = self.choose(tags=['android-v0.1.5', 'backend-v99.0.0'])
        self.assertEqual(('0.1.6', 3), (plan['version_name'], plan['version_code']))
        self.assertEqual('b' * 40, plan['source_sha'])

    def test_next_version_counts_failed_draft_reservations(self):
        plan = self.choose([reservation(), reservation(4, '0.1.3', draft=True, run='101')])
        self.assertEqual(('0.1.4', 5), (plan['version_name'], plan['version_code']))

    def test_rerun_preserves_version_and_source(self):
        plan = self.choose([reservation(draft=True)], run_id='100')
        self.assertEqual(3, plan['version_code'])
        self.assertEqual(SHA, plan['source_sha'])
        self.assertFalse(plan['published'])

    def test_retry_published_release_reuses_without_new_version(self):
        plan = self.choose([reservation()], mode='retry', tag='android-v0.1.2')
        self.assertTrue(plan['published'])
        self.assertEqual(3, plan['version_code'])

    def test_retry_missing_or_legacy_tag_fails_closed(self):
        for releases in ([], [dict(id=1, tag_name='android-v0.1.1', body='legacy debug', draft=False)]):
            with self.assertRaisesRegex(ValueError, 'reserved'):
                self.choose(releases, mode='retry', tag='android-v0.1.1')

    def test_superseded_draft_is_not_published_as_an_upgrade(self):
        with self.assertRaisesRegex(ValueError, 'superseded'):
            self.choose([reservation(draft=True), reservation(4, '0.1.3', run='101')], run_id='100')

    def test_rollback_rebuilds_old_sha_with_higher_version(self):
        plan = self.choose([reservation(), reservation(4, '0.1.3', run='101')],
                           mode='rollback', tag='android-v0.1.2', confirmed=True, reason='API checked')
        self.assertEqual(SHA, plan['source_sha'])
        self.assertEqual((5, '0.1.4'), (plan['version_code'], plan['version_name']))
        self.assertEqual('rollback', plan['mode'])

    def test_rollback_requires_published_older_target_confirmation_and_reason(self):
        releases = [reservation(), reservation(4, '0.1.3', run='101')]
        for extra in (dict(confirmed=False), dict(reason=''), dict(tag='android-v0.1.3'),
                      dict(tag='android-v0.0.1')):
            options = dict(mode='rollback', tag='android-v0.1.2', confirmed=True, reason='Checked')
            options.update(extra)
            with self.assertRaises(ValueError):
                self.choose(releases, **options)

    def test_rollback_rejects_incompatible_releases_and_main(self):
        with self.assertRaisesRegex(ValueError, 'boundary'):
            self.choose([reservation(), reservation(4, '0.1.3', run='101', contract=dict(api=2, data=1))],
                        mode='rollback', tag='android-v0.1.2', confirmed=True, reason='Checked')
        with self.assertRaisesRegex(ValueError, 'current main'):
            self.choose([reservation(), reservation(4, '0.1.3', run='101')],
                        gradle=GRADLE.replace('DataEpoch"] = 1', 'DataEpoch"] = 2'),
                        mode='rollback', tag='android-v0.1.2', confirmed=True, reason='Checked')

    def test_invalid_source_and_missing_contract_are_rejected(self):
        with self.assertRaises(ValueError):
            self.choose(source_sha='main')
        with self.assertRaises(ValueError):
            self.choose(gradle=GRADLE.replace('caloricaDataEpoch', 'missing'))

    def test_version_floor_and_future_major_are_respected(self):
        plan = self.choose([reservation(9, '1.3.9')])
        self.assertEqual((10, '1.3.10'), (plan['version_code'], plan['version_name']))
        plan = self.choose([reservation(9, '0.1.9')], gradle=GRADLE.replace('?: 3', '?: 20').replace('0.1.2', '2.0.0'))
        self.assertEqual((20, '2.0.0'), (plan['version_code'], plan['version_name']))

    def test_reason_with_newlines_and_markup_roundtrips_in_reservation(self):
        data = self.choose(mode='release', reason='line 1\n<!-- tricky -->')
        r = dict(tag_name='android-v' + data['version_name'], body=release.notes(data, 'rurkk/Calorica'))
        self.assertEqual(data['reason'], release.record(r)['reason'])

    def test_forged_tag_is_rejected(self):
        r = reservation()
        r['tag_name'] = 'android-v0.1.99'
        with self.assertRaises(ValueError):
            release.record(r)

    def test_apk_wrong_certificate_package_and_version_are_rejected(self):
        plan = self.choose()
        certificate = 'c' * 64
        good = "package: name='com.rurkk.calorica' versionCode='3' versionName='0.1.2'"
        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory) / 'build-tools' / '36.0.0'
            tools.mkdir(parents=True)
            for cert, package, valid in ((certificate, good, True), ('d' * 64, good, False),
                                         (certificate, good.replace("Code='3'", "Code='2'"), False),
                                         (certificate, good.replace('com.rurkk.calorica', 'other.package'), False)):
                with patch.object(release, 'command', side_effect=[
                    'Signer #1 certificate SHA-256 digest: ' + cert, package,
                ]):
                    if valid:
                        self.assertEqual(certificate, release.verify_apk(Path('app.apk'), plan, directory, certificate))
                    else:
                        with self.assertRaises(ValueError):
                            release.verify_apk(Path('app.apk'), plan, directory, certificate)


    def test_summary_keeps_apk_link_when_artifact_upload_fails_after_publish(self):
        import argparse
        import os
        with tempfile.TemporaryDirectory() as directory:
            previous = os.getcwd()
            try:
                os.chdir(directory)
                Path('_release').mkdir()
                plan = self.choose()
                plan['published'] = True
                plan['release_url'] = 'https://github.com/rurkk/Calorica/releases/tag/android-v0.1.2'
                Path('_release/plan.json').write_text(json.dumps(plan))
                with patch.dict(os.environ, dict(GITHUB_REPOSITORY='rurkk/Calorica',
                                GITHUB_SHA=SHA, GITHUB_RUN_ID='200', GITHUB_STEP_SUMMARY='summary')):
                    release.summary(argparse.Namespace(status='failure'))
                summary = Path('summary').read_text()
                self.assertIn('releases/download/android-v0.1.2/calorica.apk', summary)
                self.assertIn('Workflow и логи', summary)
                self.assertIn('Логи сервера', summary)
                self.assertIn(SHA, summary)
                self.assertNotIn('Публикация APK не подтверждена', summary)
            finally:
                os.chdir(previous)

    def test_failed_release_summary_does_not_claim_a_download_is_available(self):
        import argparse
        import os
        with tempfile.TemporaryDirectory() as directory:
            previous = os.getcwd()
            try:
                os.chdir(directory)
                Path('_release').mkdir()
                plan = self.choose()
                Path('_release/plan.json').write_text(json.dumps(plan))
                with patch.dict(os.environ, dict(GITHUB_REPOSITORY='rurkk/Calorica',
                                GITHUB_SHA=SHA, GITHUB_RUN_ID='200', GITHUB_STEP_SUMMARY='summary')):
                    release.summary(argparse.Namespace(status='failure'))
                summary = Path('summary').read_text()
                self.assertNotIn('Скачать APK', summary)
                self.assertIn('Публикация APK не подтверждена', summary)
            finally:
                os.chdir(previous)


class AndroidLifecycleTest(unittest.TestCase):
    def test_failed_upload_keeps_reservation_and_retry_publishes_it_once(self):
        import argparse
        import os
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        previous = os.getcwd()
        self.addCleanup(os.chdir, previous)
        os.chdir(directory.name)
        Path('app').mkdir()
        Path('app/build.gradle.kts').write_text(GRADLE)
        releases = []
        attempts = []
        fail_upload = [True]

        def fake_api(path, payload=None):
            if payload is not None:
                created = dict(payload, id=1, assets=[])
                releases.append(created)
                return created
            if '/git/matching-refs/' in path:
                return []
            return releases[0]

        def fake_command(*args, **kwargs):
            if args[:3] == ('git', 'tag', '--list'):
                return ''
            if args[:5] == ('git', '-C', 'source', 'rev-parse', 'HEAD'):
                return SHA
            attempts.append(args)
            if args[:3] == ('gh', 'release', 'upload'):
                if fail_upload[0]:
                    raise RuntimeError('upload interrupted')
                releases[0]['assets'] = [dict(name=p.name) for p in Path('_release/assets').iterdir()]
                return ''
            if args[:3] == ('gh', 'release', 'edit'):
                releases[0]['draft'] = False
                return ''
            raise AssertionError(args)

        environment = dict(GITHUB_REPOSITORY='rurkk/Calorica', GITHUB_SHA=SHA,
                           GITHUB_RUN_ID='999', GITHUB_OUTPUT=str(Path('output').resolve()),
                           ANDROID_HOME=directory.name, ANDROID_SIGNING_CERT_SHA256='c' * 64)
        with patch.dict(os.environ, environment), patch.object(release, 'inventory', side_effect=lambda _: releases), \
                patch.object(release, 'api', side_effect=fake_api), \
                patch.object(release, 'command', side_effect=fake_command), \
                patch.object(release, 'verify_apk', return_value='c' * 64):
            args = argparse.Namespace(mode='release', tag='', confirmed=False, reason='')
            release.prepare(args)
            reserved = json.loads(Path('_release/plan.json').read_text())
            self.assertEqual(1, len(releases))
            apk = Path('source/app/build/outputs/apk/release/app-release.apk')
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'signed test fixture')
            Path('source/app/build.gradle.kts').write_text(GRADLE)
            with self.assertRaisesRegex(RuntimeError, 'interrupted'):
                release.publish(args)
            self.assertTrue(releases[0]['draft'])
            release.prepare(args)
            self.assertEqual(reserved, json.loads(Path('_release/plan.json').read_text()))
            fail_upload[0] = False
            release.publish(args)
            manifest = json.loads(Path('_release/assets/release.json').read_text())
            self.assertEqual(SHA, manifest['source_sha'])
            self.assertEqual('c' * 64, manifest['certificate_sha256'])
            self.assertFalse(releases[0]['draft'])
            self.assertTrue(json.loads(Path('_release/plan.json').read_text())['published'])
            self.assertEqual('https://github.com/rurkk/Calorica/releases/tag/android-v0.1.2',
                             json.loads(Path('_release/plan.json').read_text())['release_url'])
            release.prepare(args)
            self.assertTrue(json.loads(Path('_release/plan.json').read_text())['published'])
            with self.assertRaisesRegex(ValueError, 'already published'):
                release.publish(args)
            self.assertEqual(2, sum(a[:3] == ('gh', 'release', 'upload') for a in attempts))
            self.assertEqual(1, len(releases))


if __name__ == '__main__':
    unittest.main()
