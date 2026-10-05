"""Fail release CI if R8 renamed getters invoked by LiteRT-LM JNI."""
import re
import sys
from pathlib import Path

REQUIRED = {
    "SamplerConfig": ("getTopK", "getTopP", "getTemperature", "getSeed"),
    "ThinkingConfig": ("getEnableThinking", "getThinkingTokenBudget"),
}


def verify(mapping: str) -> None:
    for short_name, methods in REQUIRED.items():
        name = "com.google.ai.edge.litertlm." + short_name
        header = re.search(r"^" + re.escape(name) + r" -> ([^:]+):$", mapping, re.M)
        if header is None or header.group(1) != name:
            raise ValueError("JNI class renamed or removed: " + name)
        end = re.search(r"^\S[^\n]* -> [^\n]+:$", mapping[header.end():], re.M)
        block = mapping[header.end():header.end() + end.start()] if end else mapping[header.end():]
        for method in methods:
            if not re.search(r"\b" + method + r"\(\)(?::\d+(?::\d+)?)? -> " + method + r"$", block, re.M):
                raise ValueError("JNI getter renamed or removed: " + name + "." + method)
    name = "com.brahmadeo.supertonic.tts.kokoro.KokoroPhonemizer"
    header = re.search(r"^" + re.escape(name) + r" -> ([^:]+):$", mapping, re.M)
    if header is None or header.group(1) != name:
        raise ValueError("Kokoro JNI bridge renamed or removed")
    end = re.search(r"^\S[^\n]* -> [^\n]+:$", mapping[header.end():], re.M)
    block = mapping[header.end():header.end() + end.start()] if end else mapping[header.end():]
    for method in ("initialize", "phonemes"):
        if not re.search(r"\b" + method + r"\(java\.lang\.String\)(?::\d+(?::\d+)?)? -> " + method + r"$", block, re.M):
            raise ValueError("Kokoro JNI method renamed or removed: " + method)


if __name__ == "__main__":
    verify(Path(sys.argv[1]).read_text())
    print("LiteRT-LM and Kokoro JNI names preserved in release R8 mapping")
