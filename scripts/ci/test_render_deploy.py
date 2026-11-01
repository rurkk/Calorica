import unittest

from render_deploy import DeployError, IMAGE_PREFIX, deploy

SERVICE = 'srv-dbjk97vlk1mc739gja40'
IMAGE = IMAGE_PREFIX + 'a' * 64


class _Render:
    def __init__(self, statuses=('update_in_progress', 'live'), runtime='image'):
        self.statuses = iter(statuses)
        self.runtime = runtime
        self.calls = []
        self.seconds = 0

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

    def deploy(self, image=IMAGE, service=SERVICE):
        return deploy('test-key', image, service, self.call, self.sleep, lambda: self.seconds)


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
