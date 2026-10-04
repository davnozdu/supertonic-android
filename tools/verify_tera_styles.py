#!/usr/bin/env python3
"""Verify the pinned NPY voice styles shipped in the APK, without ML dependencies."""
import ast
import hashlib
import json
import math
import struct
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'app/src/main/assets/tera_styles'
manifest = json.loads((root / 'manifest.json').read_text())
expected = {(voice, part) for voice in manifest['voices'] for part in ('style_dp', 'style_ttl')}
assert len(manifest['voices']) == 10 and len(expected) == 20
seen = set()
for row in manifest['files']:
    pair = row['voice'], row['part']
    assert pair in expected and pair not in seen
    seen.add(pair)
    raw = (root / f"{row['voice']}_{row['part']}.npy").read_bytes()
    assert len(raw) == row['size'] and hashlib.sha256(raw).hexdigest() == row['sha256']
    assert raw[:8] == b'\x93NUMPY\x01\x00'
    length = int.from_bytes(raw[8:10], 'little')
    header = ast.literal_eval(raw[10:10+length].decode('ascii').strip())
    shape = (1, 8, 16) if row['part'] == 'style_dp' else (1, 50, 256)
    assert header['shape'] == shape and header['descr'] == '<f4' and not header['fortran_order']
    payload = raw[10+length:]
    assert len(payload) == math.prod(shape) * 4
    assert all(math.isfinite(x[0]) for x in struct.iter_unpack('<f', payload))
assert seen == expected
print('Tera: all 10 voices, 20 SHA-verified styles, correct shapes and finite samples')
