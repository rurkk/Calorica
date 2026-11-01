#!/usr/bin/env python3
"""Deploy one immutable Calorica image and wait for Render to report it live."""
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
IMAGE_PREFIX = 'ghcr.io/rurkk/calorica-backend@sha256:'
READINESS_URL = 'https://calorica-backend.onrender.com/actuator/health/readiness'
READINESS_TIMEOUT = 90


class DeployError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def probe_readiness(url, timeout, opener=None):
    # Public endpoint: never attach the administrative Render API key.
    req = urllib.request.Request(url, headers={'Accept': 'application/json'})
    open_request = opener or urllib.request.build_opener(NoRedirect()).open
    try:
        with open_request(req, timeout=timeout) as response:
            data = response.read(65_537)
            if response.status != 200 or len(data) > 65_536:
                return False
            body = json.loads(data)
            return isinstance(body, dict) and body.get('status') == 'UP'
    except urllib.error.HTTPError as error:
        if error.code in {401, 403, 404}:
            raise DeployError(f'Public readiness returns HTTP {error.code}; inspect server configuration.') from None
        return False
    except (urllib.error.URLError, OSError, ValueError):
        return False


def wait_for_readiness(check=probe_readiness, sleep=time.sleep, clock=time.monotonic):
    deadline = clock() + READINESS_TIMEOUT
    while True:
        remaining = deadline - clock()
        if remaining <= 0:
            break
        ready = check(READINESS_URL, timeout=min(10, remaining))
        if ready and clock() <= deadline:
            print('Public readiness: UP', flush=True)
            return
        remaining = deadline - clock()
        if remaining > 0:
            sleep(min(2, remaining))
    raise DeployError('Public readiness is not UP after 90 seconds; the deploy is not ready.')


def request(token, path, method='GET', payload=None):
    req = urllib.request.Request(
        'https://api.render.com/v1' + path,
        data=json.dumps(payload).encode() if payload is not None else None,
        method=method,
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
                 'Accept': 'application/json'},
    )
    try:
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        # Provider responses can contain configuration or credentials; omit their body.
        raise DeployError(f'Render API returns HTTP {error.code}; inspect the Render dashboard.') from None
    except (urllib.error.URLError, TimeoutError, ValueError):
        raise DeployError('Render API request fails; inspect the Render dashboard before retrying.') from None


def deploy(token, image, service_id, call=request, sleep=time.sleep, clock=time.monotonic,
           wait_ready=wait_for_readiness):
    if not re.fullmatch(re.escape(IMAGE_PREFIX) + '[a-f0-9]{64}', image):
        raise DeployError('Expected an immutable Calorica GHCR image digest.')
    if not token or any(char.isspace() for char in token):
        raise DeployError('RENDER_API_KEY is missing or invalid.')
    if service_id != 'srv-dbjk97vlk1mc739gja40':
        raise DeployError('Only the registered Calorica service can be deployed.')
    path = '/services/' + service_id
    service = call(token, path)
    if service.get('serviceDetails', {}).get('runtime') != 'image':
        raise DeployError('Configure the Render service with an existing image source first.')
    if not service.get('imagePath', '').startswith(IMAGE_PREFIX):
        raise DeployError('The Render service must already use the Calorica image repository.')
    result = call(token, path + '/deploys', 'POST', {'imageUrl': image})
    deploy_id = result.get('id', '')
    if not re.fullmatch(r'dep-[a-z0-9]+', deploy_id):
        raise DeployError('Render does not return a deploy ID; inspect Events before retrying.')
    url = f'https://dashboard.render.com/web/{service_id}/deploys/{deploy_id}'
    print(f'Render deploy: {url}', flush=True)
    print(f'Image: {image}', flush=True)
    deadline = clock() + 600
    previous_status = None
    while clock() < deadline:
        current = call(token, path + '/deploys/' + deploy_id)
        status = current.get('status')
        if status != previous_status:
            print(f'Render status: {status}', flush=True)
            previous_status = status
        if status == 'live':
            if current.get('image', {}).get('ref') != image:
                raise DeployError(f'Render reports a different image for this deploy: {url}')
            wait_ready()
            # Persist only a successful digest for future restarts/manual deploys.
            saved = call(token, path, 'PATCH', {'autoDeployTrigger': 'off', 'image': {
                'ownerId': service['ownerId'], 'imagePath': image,
            }})
            if saved.get('imagePath') != image:
                raise DeployError(f'Render does not persist the live image: {url}')
            return url
        if status in {'build_failed', 'update_failed', 'pre_deploy_failed', 'canceled', 'deactivated'}:
            raise DeployError(f'Render deploy ends with {status}: {url}')
        sleep(10)
    raise DeployError(f'Render deploy is not live after 10 minutes: {url}')


def main():
    registry = json.loads((ROOT / 'deploy/access/infrastructure.json').read_text())
    image = os.environ.get('RENDER_IMAGE', '')
    try:
        url = deploy(os.environ.get('RENDER_API_KEY', ''), image, registry['render_service_id'])
    except DeployError as error:
        print(str(error), flush=True)
        return 1
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as output:
            output.write(f'### Backend deployed\n\nImage: `{image}`\n\n'
                         f'[Readiness: UP]({READINESS_URL})\n\n'
                         f'[Render deploy]({url}) · '
                         f'[API]({registry["api_base_url"]}) · '
                         f'[Runtime logs]({registry["shared_logs_url"]})\n\n'
                         f'Workflow SHA: `{os.environ.get("GITHUB_SHA", "local")}`\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
