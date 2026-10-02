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

apk = fetch('ruvoice.apk',
    'https://github.com/kost-t-human/ruvoice-tts/releases/download/v0.18.0/ruvoice-tts-0.18.0-lite.apk',
    '7ec33e97c2ea102db85025824b43f6dc514a5f1ed92e7cdfc94ae62c979ce555')
aar = fetch('executorch.aar',
    'https://ossci-android.s3.amazonaws.com/executorch/release/1.5.0-xnnpack/executorch.aar',
    'dcb50be130e1e45d898b846f30dedf2d627fc410191b3fb4e870a2b104b89286')
out = ROOT / 'app/libs/executorch-1.5.0-arm64.aar'
out.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(aar) as src, zipfile.ZipFile(apk) as release, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as dst:
    for entry in src.infolist():
        if not entry.filename.startswith('jni/'):
            dst.writestr(entry.filename, src.read(entry))
    dst.writestr('jni/arm64-v8a/libexecutorch.so', release.read('lib/arm64-v8a/libexecutorch.so'))
print('Prepared verified ARM64 ExecuTorch 1.5.0 runtime')
