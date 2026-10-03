"""Verify synthetic PNG fixtures are actual decodable PNG payloads, not placeholders."""
import base64
from pathlib import Path
import re
import unittest
import zlib


class FixtureImageTests(unittest.TestCase):
    def test_generated_image_fixture_has_valid_png_chunks_and_pixel_stream(self):
        path = Path(__file__).resolve().parents[1] / 'app/src/androidTest/java/com/charactermemory/android/P2FixtureDispatcher.kt'
        fixtures = re.findall(r'data:image/png;base64,([A-Za-z0-9+/=]+)', path.read_text(encoding='utf-8'))
        self.assertTrue(fixtures, 'missing generated image fixture')
        for encoded in fixtures:
            payload = base64.b64decode(encoded, validate=True)
            self.assertEqual(payload[:8], b'\x89PNG\r\n\x1a\n')
            offset, pixels, ended = 8, b'', False
            while offset < len(payload):
                length = int.from_bytes(payload[offset:offset + 4], 'big')
                kind = payload[offset + 4:offset + 8]
                data = payload[offset + 8:offset + 8 + length]
                crc = int.from_bytes(payload[offset + 8 + length:offset + 12 + length], 'big')
                self.assertEqual(len(data), length)
                self.assertEqual(zlib.crc32(kind + data), crc, f'{kind!r} PNG checksum invalid')
                if kind == b'IDAT':
                    pixels += data
                ended = kind == b'IEND'
                offset += 12 + length
            self.assertEqual(offset, len(payload))
            self.assertTrue(ended)
            self.assertTrue(zlib.decompress(pixels), 'empty PNG pixel stream')


if __name__ == '__main__':
    unittest.main()
