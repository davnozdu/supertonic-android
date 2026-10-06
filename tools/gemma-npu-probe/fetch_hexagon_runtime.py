#!/usr/bin/env python3
"""Stage published native comparison libraries; compile only the tiny JNI bridge."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request

root = Path(__file__).parent
manifest = json.loads((root / "hexagon-runtime.json").read_text())
destination = root / "app/build/hexagon-runtime/arm64-v8a"
destination.mkdir(parents=True, exist_ok=True)

def fetch(spec):
    assert spec["path"].startswith("llama.cpp/lib/") and spec["path"].endswith(".so")
    url = "https://huggingface.co/{}/resolve/{}/{}".format(manifest["repo"], manifest["revision"], spec["path"])
    with urllib.request.urlopen(url, timeout=90) as response:
        data = response.read()
    assert len(data) == spec["size"], spec["path"]
    assert hashlib.sha256(data).hexdigest() == spec["sha256"], spec["path"]
    (destination / Path(spec["path"]).name).write_bytes(data)
    print("Verified", spec["path"])

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    list(pool.map(fetch, manifest["files"]))
