#!/usr/bin/env python3
"""Stage the pinned prebuilt llama.cpp + ggml-hexagon runtime (sizes and SHA-256 checked)."""
import hashlib, json, sys, urllib.request
from pathlib import Path
root = Path(__file__).parent
manifest = json.loads((root / "runtime.json").read_text())
destination = Path(sys.argv[1]); destination.mkdir(parents=True, exist_ok=True)
for spec in manifest["files"]:
    target = destination / Path(spec["path"]).name
    if target.is_file() and target.stat().st_size == spec["size"] and hashlib.sha256(target.read_bytes()).hexdigest() == spec["sha256"]:
        continue
    url = "https://huggingface.co/{}/resolve/{}/{}".format(manifest["repo"], manifest["revision"], spec["path"])
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    assert len(data) == spec["size"], spec["path"]
    assert hashlib.sha256(data).hexdigest() == spec["sha256"], spec["path"]
    target.write_bytes(data); print("verified", target.name)
