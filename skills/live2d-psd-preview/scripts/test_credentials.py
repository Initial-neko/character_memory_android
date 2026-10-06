import contextlib
import io
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from live2d_env import load_token


class CredentialTests(unittest.TestCase):
    def test_explicit_dotenv_wins_without_reading_system_or_yaml(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path = root / '.env'
            path.write_text('export MSIMG_API_KEY="test-only-from-file"\n', encoding='utf-8')
            (root / 'config.yaml').write_text('msimg_api_key: test-only-old-yaml')
            with patch.dict(os.environ, {'MSIMG_API_KEY': 'test-only-system'}):
                self.assertEqual(load_token(path), 'test-only-from-file')
                self.assertEqual(os.environ['MSIMG_API_KEY'], 'test-only-system')

    def test_token_alias_and_bom(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / '.env'
            path.write_text("# ignored\nMODELSCOPE_API_TOKEN='test-only-alias'\n", encoding='utf-8-sig')
            self.assertEqual(load_token(path), 'test-only-alias')

    def test_missing_file_does_not_fall_back(self):
        with patch.dict(os.environ, {'MSIMG_API_KEY': 'test-only-system'}):
            with self.assertRaisesRegex(ValueError, 'explicit .env'):
                load_token(Path('missing-test-only.env'))

    def test_missing_key_does_not_disclose_unrelated_values(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / '.env'
            path.write_text('UNRELATED_SECRET=test-only-unrelated\nMSIMG_API_KEY=\n')
            with self.assertRaises(ValueError) as caught:
                load_token(path)
            self.assertNotIn('test-only-unrelated', str(caught.exception))

    def test_reader_never_prints_token(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / '.env'
            path.write_text('MSIMG_API_KEY=test-only-value\n')
            output = io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                load_token(path)
            self.assertEqual(output.getvalue(), '')


if __name__ == '__main__':
    unittest.main()
