import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import json
from generate_psd import checked_api, download_url, generate, DEFAULT_API


class ApiBoundaryTests(unittest.TestCase):
    def run_fixture(self, folder, *, stream_error=False, redirect=False):
        root = Path(folder)
        token = 'test-only-private-token'
        (root / '.env').write_text('MSIMG_API_KEY=' + token)
        (root / 'image.png').write_bytes(b'local input fixture')
        calls = []

        class Response:
            status_code = 200
            content = b'8BPS-fixture-only'
            def __init__(self, data=None): self.data = data
            def json(self): return self.data
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def iter_lines(self, **kwargs):
                if stream_error:
                    yield b'event: error'
                    yield ('data: ' + json.dumps(token)).encode()
                else:
                    yield b'event: complete'
                    yield ('data: ' + json.dumps([{'url': DEFAULT_API + '/file.psd'}])).encode()

        class Session:
            headers = {}
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def get(self, url, **kwargs):
                calls.append((url, kwargs))
                response = Response()
                if redirect: response.status_code = 302
                return response
            def post(self, url, **kwargs):
                calls.append((url, kwargs))
                return Response(['/tmp/input.png'] if url.endswith('/upload') else {'event_id': 'fixture-event'})

        with patch.dict('sys.modules', {'requests': SimpleNamespace(Session=Session)}):
            report = generate(SimpleNamespace(env_file=root / '.env', api_base=DEFAULT_API,
                image=root / 'image.png', output=root / 'output', resolution=1024,
                seed=42, tblr_split=False, wait_seconds=10))
        self.assertNotIn(token, json.dumps(report))
        self.assertNotIn(token, (root / 'output/report.json').read_text())
        self.assertTrue(all(kwargs['allow_redirects'] is False for _, kwargs in calls))
        return report, calls

    def test_fixture_upload_submit_stream_download_and_psd_hash(self):
        with tempfile.TemporaryDirectory() as folder:
            report, calls = self.run_fixture(folder)
            self.assertEqual(report['status'], 'PASS')
            self.assertEqual(report['psd_bytes'], len(b'8BPS-fixture-only'))
            self.assertEqual(len(calls), 5)
            self.assertTrue((Path(folder) / 'output/generated.psd').exists())

    def test_server_error_never_leaks_echoed_credentials(self):
        with tempfile.TemporaryDirectory() as folder:
            report, _ = self.run_fixture(folder, stream_error=True)
            self.assertEqual(report['status'], 'FAIL')
            self.assertFalse((Path(folder) / 'output/generated.psd').exists())

    def test_redirect_is_rejected_without_further_authenticated_requests(self):
        with tempfile.TemporaryDirectory() as folder:
            report, calls = self.run_fixture(folder, redirect=True)
            self.assertEqual(report['status'], 'FAIL')
            self.assertEqual(report['http_status'], 302)
            self.assertEqual(len(calls), 1)

    def test_missing_credentials_fails_before_network_client(self):
        with patch.dict('sys.modules', {'requests': None}):
            with self.assertRaises(ValueError):
                generate(SimpleNamespace(env_file='missing-test-only.env'))

    def test_rejects_unrelated_host_and_url_credentials(self):
        for url in ['http://example.org', 'https://example.org',
                    DEFAULT_API + '.evil.test', DEFAULT_API + '/?token=test-only',
                    DEFAULT_API.replace('https://', 'https://user:pass@')]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                checked_api(url)

    def test_output_host_is_checked_before_authenticated_download(self):
        with self.assertRaises(ValueError):
            download_url({'url': 'https://other.example/file.psd'}, DEFAULT_API)
        self.assertEqual(download_url({'path': '/tmp/file.psd'}, DEFAULT_API),
                         DEFAULT_API + '/gradio_api/file=/tmp/file.psd')

    def test_existing_output_is_preserved_without_importing_network_client(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / '.env').write_text('MSIMG_API_KEY=test-only-file\n')
            (root / 'image.png').write_bytes(b'test-only')
            with patch.dict('sys.modules', {'requests': None}), self.assertRaisesRegex(ValueError, 'new output'):
                generate(SimpleNamespace(env_file=root / '.env', api_base=DEFAULT_API,
                                         image=root / 'image.png', output=root))


if __name__ == '__main__':
    unittest.main()
