#!/usr/bin/env python3
"""Pin the Android runtime used by ruvoice 0.18.0; no model conversion.

Its ARM64 ExecuTorch build disables LLM kernels and uses outline atomics,
avoiding the official AAR's ARMv8.1-only atomics on older ARM64 phones.
Java classes come from the official 1.5.0 XNNPACK AAR.
"""
import hashlib
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.silero-runtime-cache'
CACHE.mkdir(exist_ok=True)

def fetch(name, url, digest):
    path = CACHE / name
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        part = path.with_suffix('.part')
        with urllib.request.urlopen(url, timeout=120) as src, part.open('wb') as dst:
            import shutil
            shutil.copyfileobj(src, dst)
        if hashlib.sha256(part.read_bytes()).hexdigest() != digest:
            part.unlink()
            raise RuntimeError('Runtime checksum mismatch: ' + name)
        part.replace(path)
    return path

out = ROOT / 'app/libs/executorch-1.5.0-arm64.aar'
out.parent.mkdir(exist_ok=True)
verified = fetch('executorch-1.5.0-arm64.aar',
    'https://github.com/davnozdu/supertonic-android/releases/download/russian-resources-v1/executorch-1.5.0-arm64.aar',
    '6c870ec2ad275afcd695957241763ce471a59398ffba31242acd24304427dc37')
import shutil
shutil.copyfile(verified,out)
print('Prepared verified mirrored ARM64 ExecuTorch 1.5.0 runtime')
