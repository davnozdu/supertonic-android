"""Fail release CI if R8 renamed getters invoked by LiteRT-LM JNI."""
import re
import sys
import struct
import zipfile
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


def verify_kokoro_apk(path: Path) -> None:
    """Check native declarations in DEX itself: unchanged native methods may be
    absent from R8's mapping, which is a retrace map, not a method inventory.
    """
    required = {("initialize", "(Ljava/lang/String;)Z"),
                ("phonemes", "(Ljava/lang/String;)Ljava/lang/String;")}
    found = set()
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if not re.fullmatch(r"classes(?:\d+)?\.dex", name):
                continue
            data = apk.read(name)
            if data[:4] != b"dex\n":
                raise ValueError("Invalid DEX: " + name)
            def u32(pos):
                return struct.unpack_from("<I", data, pos)[0]
            def u16(pos):
                return struct.unpack_from("<H", data, pos)[0]
            def uleb(pos):
                value = 0
                for shift in range(0, 35, 7):
                    byte = data[pos]; pos += 1
                    value |= (byte & 127) << shift
                    if byte < 128:
                        return value, pos
                raise ValueError("Invalid DEX ULEB128")
            strings = []
            for i in range(u32(0x38)):
                pos = u32(u32(0x3c) + 4*i)
                _, pos = uleb(pos)
                strings.append(data[pos:data.index(0, pos)].decode("utf-8", errors="replace"))
            types = [strings[u32(u32(0x44) + 4*i)] for i in range(u32(0x40))]
            for i in range(u32(0x60)):
                pos = u32(0x64) + 32*i
                if types[u32(pos)] != "Lcom/brahmadeo/supertonic/tts/kokoro/KokoroPhonemizer;":
                    continue
                pos = u32(pos + 24)
                counts = []
                for _ in range(4):
                    count, pos = uleb(pos); counts.append(count)
                for _ in range(counts[0] + counts[1]):
                    _, pos = uleb(pos); _, pos = uleb(pos)
                for count in counts[2:]:
                    method_index = 0
                    for _ in range(count):
                        delta, pos = uleb(pos); method_index += delta
                        access, pos = uleb(pos); code, pos = uleb(pos)
                        method = u32(0x5c) + 8*method_index
                        proto = u32(0x4c) + 12*u16(method + 2)
                        params = u32(proto + 8)
                        arguments = "" if not params else "".join(types[u16(params + 4 + 2*j)] for j in range(u32(params)))
                        signature = "(" + arguments + ")" + types[u32(proto + 4)]
                        if access & 0x100 and not access & 0x8 and code == 0:
                            found.add((strings[u32(method + 4)], signature))
    if not required <= found:
        raise ValueError("Kokoro JNI native declarations missing from APK: " + repr(required - found))


if __name__ == "__main__":
    verify(Path(sys.argv[1]).read_text())
    apks = list(Path(sys.argv[2]).glob("*.apk"))
    if not apks:
        raise ValueError("No APKs for Kokoro JNI verification")
    for apk in apks:
        verify_kokoro_apk(apk)
    print("LiteRT-LM names preserved; Kokoro native declarations verified in APK DEX")
