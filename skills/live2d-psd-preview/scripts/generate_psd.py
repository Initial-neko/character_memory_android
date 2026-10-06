"""Explicit image upload to See-Through; no ambient token, redirects or retries."""
import argparse
import hashlib
import json
import time
from pathlib import Path
from urllib.parse import quote, urlparse

from live2d_env import load_token

DEFAULT_API = 'https://studio-ljsabc-see-through.api-inference.modelscope.net'


def checked_api(value):
    url = urlparse(value)
    if (url.scheme != 'https' or not url.hostname
            or not url.hostname.endswith('.api-inference.modelscope.net')
            or url.username or url.password or url.port or url.query or url.fragment
            or url.path not in {'', '/'}):
        raise ValueError('Use an explicitly authorized HTTPS ModelScope API origin')
    return value.rstrip('/')


def download_url(asset, base):
    url = asset if isinstance(asset, str) else asset.get('url')
    if not url and isinstance(asset, dict) and asset.get('path'):
        url = base + '/gradio_api/file=' + quote(asset['path'], safe='/')
    parsed = urlparse(url or '')
    if parsed.scheme != 'https' or parsed.netloc != urlparse(base).netloc or parsed.username:
        raise ValueError('Output URL is outside the authorized API origin')
    return url


def generate(args):
    # All checks precede importing the network client or making requests.
    token = load_token(args.env_file)
    base = checked_api(args.api_base)
    image = Path(args.image)
    if not image.is_file():
        raise ValueError('Input image does not exist')
    output = Path(args.output)
    if output.exists():
        raise ValueError('Use a new output directory to preserve prior results')
    import requests

    output.mkdir(parents=True)
    report = {'status': 'FAIL', 'input_sha256': hashlib.sha256(image.read_bytes()).hexdigest(),
              'resolution': args.resolution, 'seed': args.seed, 'tblr_split': args.tblr_split,
              'credential_source': 'explicit .env', 'api_origin': base}
    started = time.monotonic()
    with requests.Session() as session:
        session.trust_env = False  # No implicit netrc credentials or proxy auth.
        session.headers['Authorization'] = 'Bearer ' + token

        def checked(response):
            if response.status_code != 200:
                report['http_status'] = response.status_code
                raise RuntimeError('API request failed or redirected')
            return response

        try:
            checked(session.get(base + '/config', timeout=40, allow_redirects=False))
            with image.open('rb') as file:
                uploaded = checked(session.post(base + '/gradio_api/upload',
                    files={'files': (image.name, file)}, timeout=90, allow_redirects=False)).json()
            payload = {'data': [{'path': uploaded[0], 'meta': {'_type': 'gradio.FileData'}},
                                 args.resolution, args.seed, args.tblr_split]}
            event_id = checked(session.post(base + '/gradio_api/call/inference',
                json=payload, timeout=60, allow_redirects=False)).json()['event_id']
            report['event_id'] = event_id
            deadline = time.monotonic() + args.wait_seconds
            event = None
            with session.get(base + '/gradio_api/call/inference/' + quote(event_id, safe=''),
                             stream=True, timeout=(30, 30), allow_redirects=False) as response:
                checked(response)
                for raw in response.iter_lines(chunk_size=1):
                    if time.monotonic() > deadline:
                        raise TimeoutError('Local wait budget exceeded; remote state unknown')
                    line = raw.decode('utf-8')
                    if line.startswith('event:'):
                        event = line[6:].strip()
                    elif line.startswith('data:') and event == 'error':
                        raise RuntimeError('Inference returned an error')
                    elif line.startswith('data:') and event == 'complete':
                        asset = json.loads(line[5:])[0]
                        result = checked(session.get(download_url(asset, base),
                            timeout=120, allow_redirects=False)).content
                        if not result.startswith(b'8BPS'):
                            raise ValueError('Output is not a PSD')
                        (output / 'generated.psd').write_bytes(result)
                        report.update(status='PASS', psd_bytes=len(result),
                                      psd_sha256=hashlib.sha256(result).hexdigest())
                        break
                if report['status'] != 'PASS':
                    raise RuntimeError('Stream ended without a PSD result')
        except Exception as error:
            # Do not write SDK exceptions, headers, server bodies or token values.
            report['error_type'] = type(error).__name__
            if isinstance(error, TimeoutError):
                report['remote_state'] = 'UNKNOWN'
        finally:
            if report.get('event_id') and report['status'] != 'PASS':
                report.setdefault('remote_state', 'UNKNOWN')
            report['elapsed_seconds'] = round(time.monotonic() - started, 2)
            serialized = json.dumps(report, indent=2).replace(token, '[REDACTED]')
            (output / 'report.json').write_text(serialized, encoding='utf-8')
            report = json.loads(serialized)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--api-base', default=DEFAULT_API)
    parser.add_argument('--resolution', type=int, choices=range(768, 1537, 64), default=1024)
    parser.add_argument('--seed', type=int, choices=range(10000), default=42)
    parser.add_argument('--tblr-split', action='store_true')
    parser.add_argument('--wait-seconds', type=int, choices=range(1, 3601), default=600)
    args = parser.parse_args()
    try:
        report = generate(args)
    except (ValueError, OSError):
        print(json.dumps({'status': 'FAIL', 'error': 'Check explicit .env, input and API origin'}))
        return 1
    print(json.dumps(report))
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
