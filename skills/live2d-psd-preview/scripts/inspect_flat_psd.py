import hashlib
import json
import struct
import sys
import io
import zlib
from pathlib import Path
from PIL import Image

source = Path(sys.argv[1])
out = Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
raw = source.read_bytes()
signature, version, channels, height, width, depth, mode = struct.unpack('>4sH6xHIIHH', raw[:26])
if signature != b'8BPS' or version != 1 or depth != 8 or mode != 3:
    raise ValueError('Supported: PSD v1 RGB 8-bit only; use the upstream reader for other formats.')
stream = io.BytesIO(raw)
stream.seek(26)
def unpack(fmt):
    return struct.unpack('>' + fmt, stream.read(struct.calcsize('>' + fmt)))
for _ in range(2):
    size = unpack('I')[0]
    stream.seek(size, 1)
section_size = unpack('I')[0]
info_size = unpack('I')[0]
count = abs(unpack('h')[0])
records = []
for _ in range(count):
    top, left, bottom, right = unpack('iiii')
    channel_info = [unpack('hI') for _ in range(unpack('H')[0])]
    blend = stream.read(12)
    extra_size = unpack('I')[0]
    extra_end = stream.tell() + extra_size
    for extra_kind in ('mask', 'blend-range'):
        size = unpack('I')[0]
        value = stream.read(size)
        if extra_kind == 'mask' and size:
            raise ValueError('Layer masks are unsupported by this flat diagnostic extractor.')
        if extra_kind == 'blend-range' and size and (size % 4 or value != b'\x00\x00\xff\xff' * (size // 4)):
            raise ValueError('Non-default blending ranges are unsupported by this flat diagnostic extractor.')
    if blend[4:8] != b'norm' or blend[8] != 255:
        raise ValueError('Supported: normal blending and full layer opacity only.')
    name = stream.read(unpack('B')[0]).decode('latin1')
    stream.seek(extra_end)
    records.append((name, (left, top, right, bottom), channel_info, blend[8], blend[10]))

def decode_channel(data, w, h):
    compression = struct.unpack('>H', data[:2])[0]
    if compression == 0:
        result = data[2:]
    elif compression == 2:
        result = zlib.decompress(data[2:])
    elif compression == 1:
        lengths = struct.unpack('>' + 'H' * h, data[2:2 + h * 2])
        pos = 2 + h * 2
        result = bytearray()
        for length in lengths:
            row = data[pos:pos + length]
            pos += length
            i = 0
            expanded = bytearray()
            while i < len(row):
                n = row[i]
                i += 1
                if n <= 127:
                    expanded.extend(row[i:i + n + 1])
                    i += n + 1
                elif n >= 129:
                    expanded.extend(row[i:i + 1] * (257 - n))
                    i += 1
            assert len(expanded) == w
            result.extend(expanded)
        result = bytes(result)
    else:
        raise ValueError(f'Unsupported compression: {compression}')
    assert len(result) == w * h
    return Image.frombytes('L', (w, h), result)

composite = Image.new('RGBA', (width, height))
layers = []
for index, (name, bounds, channel_info, opacity, flags) in enumerate(records, 1):
    w, h = bounds[2] - bounds[0], bounds[3] - bounds[1]
    if w <= 0 or h <= 0 or not all(cid in [c for c, _ in channel_info] for cid in (0, 1, 2, -1)):
        raise ValueError('Unsupported group/empty layer or layer without RGBA channels.')
    bands = {cid: decode_channel(stream.read(size), w, h) for cid, size in channel_info}
    crop = Image.merge('RGBA', [bands[cid] for cid in (0, 1, 2, -1)])
    if not flags & 2:
        composite.alpha_composite(crop, (bounds[0], bounds[1]))
    filename = f'layer-{index:02d}.png'
    crop.save(out / filename)
    layers.append({'index': index, 'name': name, 'bounds': bounds,
                   'alpha_extrema': crop.getchannel('A').getextrema(), 'preview': filename,
                   'visible': not bool(flags & 2), 'opacity': opacity})
hist = composite.getchannel('A').histogram()
names = {layer['name'] for layer in layers}
required = ['face', 'eyewhite', 'irides', 'eyelash', 'mouth', 'front hair', 'back hair', 'topwear']
report = {'source_name': source.name, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
          'header': {'signature': signature.decode(), 'version': version, 'channels': channels,
                     'width': width, 'height': height, 'depth': depth, 'color_mode': mode},
          'rgb_8bit': depth == 8 and mode == 3, 'composite_transparent_pixels': hist[0],
          'composite_partial_alpha_pixels': sum(hist[1:255]), 'layers': layers,
          'missing_required_names': [name for name in required if name not in names],
          'mouth_maximum_open_verified': False,
          'notes': ['Layer semantic recognition must be confirmed by Auto_Vtb output.',
                    'Maximum mouth opening and deformation quality require rendered pose checks.']}
(out / 'psd-precheck.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
composite.save(out / 'psd-composite.png')
print(json.dumps({key: report[key] for key in ['sha256', 'header', 'rgb_8bit', 'composite_transparent_pixels', 'missing_required_names']}, indent=2))
