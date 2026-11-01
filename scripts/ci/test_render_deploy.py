import unittest
import urllib.error

from render_deploy import DeployError, IMAGE_PREFIX, deploy, probe_readiness, wait_for_readiness

SERVICE = 'srv-dbjk97vlk1mc739gja40'
IMAGE = IMAGE_PREFIX + 'a' * 64


class _Render:
    def __init__(self, statuses=('update_in_progress', 'live'), runtime='image'):
        self.statuses = iter(statuses)
        self.runtime = runtime
        self.calls = []
        self.seconds = 0
        self.ready = False
        self.readiness_failure = False

    def call(self, token, path, method='GET', payload=None):
        self.calls.append((method, path, payload))
        if method == 'POST':
            return {'id': 'dep-example'}
        if '/deploys/' in path:
            return {'status': next(self.statuses, 'update_in_progress'), 'image': {'ref': IMAGE}}
        return {'ownerId': 'owner', 'imagePath': IMAGE,
                'serviceDetails': {'runtime': self.runtime}}

    def sleep(self, seconds):
        self.seconds += seconds

    def wait_ready(self):
        if self.readiness_failure:
            raise DeployError('Readiness stays DOWN')
        self.ready = True

    def deploy(self, image=IMAGE, service=SERVICE):
        return deploy('test-key', image, service, self.call, self.sleep, lambda: self.seconds,
                      wait_ready=self.wait_ready)


class RenderDeployTest(unittest.TestCase):
    def test_deploys_exact_digest_and_persists_it_only_after_live(self):
        render = _Render()
        url = render.deploy()
        self.assertTrue(url.endswith('/deploys/dep-example'))
        self.assertEqual(render.calls[1], ('POST', '/services/' + SERVICE + '/deploys',
                                         {'imageUrl': IMAGE}))
        self.assertEqual(render.calls[-1], ('PATCH', '/services/' + SERVICE,
                                          {'autoDeployTrigger': 'off', 'image': {
                                              'ownerId': 'owner', 'imagePath': IMAGE}}))
        self.assertEqual(render.seconds, 10)
        self.assertTrue(render.ready)

    def test_rejects_mutable_or_unrelated_images_without_calling_render(self):
        for image in ('ghcr.io/rurkk/calorica-backend:latest', IMAGE.replace('rurkk', 'other')):
            render = _Render()
            with self.assertRaises(DeployError):
                render.deploy(image=image)
            self.assertEqual(render.calls, [])

    def test_rejects_an_unrelated_service_without_calling_render(self):
        render = _Render()
        with self.assertRaises(DeployError):
            render.deploy(service='srv-other')
        self.assertEqual(render.calls, [])

    def test_does_not_deploy_to_a_git_source(self):
        render = _Render(runtime='docker')
        with self.assertRaises(DeployError):
            render.deploy()
        self.assertEqual(len(render.calls), 1)

    def test_failed_deploy_does_not_replace_the_saved_image(self):
        for status in ('build_failed', 'update_failed', 'pre_deploy_failed', 'canceled', 'deactivated'):
            render = _Render(statuses=(status,))
            with self.assertRaises(DeployError):
                render.deploy()
            self.assertNotIn('PATCH', [call[0] for call in render.calls])

    def test_times_out_instead_of_reporting_a_stuck_deploy_as_live(self):
        render = _Render(statuses=())
        with self.assertRaises(DeployError):
            render.deploy()
        self.assertEqual(render.seconds, 600)
        self.assertNotIn('PATCH', [call[0] for call in render.calls])

    def test_rejects_a_live_deploy_with_a_different_digest(self):
        render = _Render(statuses=('live',))
        other_image = IMAGE_PREFIX + 'b' * 64
        with self.assertRaises(DeployError):
            render.deploy(image=other_image)
        self.assertNotIn('PATCH', [call[0] for call in render.calls])

    def test_live_with_unhealthy_database_does_not_save_the_image_or_report_success(self):
        render = _Render(statuses=('live',))
        render.readiness_failure = True
        with self.assertRaises(DeployError):
            render.deploy()
        self.assertNotIn('PATCH', [call[0] for call in render.calls])


class _Response:
    def __init__(self, body, status=200):
        self.body = body
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, size):
        return self.body[:size]


class PublicReadinessTest(unittest.TestCase):
    def test_only_successful_json_up_is_ready_and_no_authorization_is_sent(self):
        for body, status, expected in [
            (b'{"status":"UP"}', 200, True),
            (b'{"status":"DOWN"}', 200, False),
            (b'{"status":"UP"}', 503, False),
            (b'<html>Render is starting</html>', 200, False),
            (b'["UP"]', 200, False),
            (b'x' * 65_537, 200, False),
        ]:
            def opener(request, timeout):
                self.assertIsNone(request.get_header('Authorization'))
                self.assertEqual(request.get_method(), 'GET')
                self.assertEqual(timeout, 10)
                return _Response(body, status)
            self.assertEqual(probe_readiness('https://example.test/readiness', 10, opener), expected)

    def test_network_and_temporary_http_failure_allow_another_probe(self):
        for error in (urllib.error.URLError('offline'),
                      urllib.error.HTTPError('https://example.test', 503, 'unavailable', {}, None)):
            def opener(request, timeout):
                raise error
            self.assertFalse(probe_readiness('https://example.test', 10, opener))

    def test_missing_or_protected_readiness_endpoint_is_a_configuration_error(self):
        def opener(request, timeout):
            raise urllib.error.HTTPError('https://example.test', 401, 'unauthorized', {}, None)
        with self.assertRaises(DeployError):
            probe_readiness('https://example.test', 10, opener)

    def test_cold_server_is_retried_until_ready(self):
        seconds = [0]
        calls = []
        def check(url, timeout):
            calls.append(timeout)
            return len(calls) == 3
        def sleep(delay):
            seconds[0] += delay
        wait_for_readiness(check, sleep, lambda: seconds[0])
        self.assertEqual(calls, [10, 10, 10])
        self.assertEqual(seconds[0], 4)

    def test_overall_deadline_also_bounds_the_last_network_probe(self):
        seconds = [0]
        timeouts = []
        def check(url, timeout):
            timeouts.append(timeout)
            seconds[0] += timeout
            return False
        def sleep(delay):
            seconds[0] += delay
        with self.assertRaises(DeployError):
            wait_for_readiness(check, sleep, lambda: seconds[0])
        self.assertEqual(seconds[0], 90)
        self.assertLess(timeouts[-1], 10)

    def test_a_late_success_does_not_pass_an_expired_deadline(self):
        seconds = [0]
        def check(url, timeout):
            seconds[0] += 91
            return True
        with self.assertRaises(DeployError):
            wait_for_readiness(check, lambda delay: None, lambda: seconds[0])
