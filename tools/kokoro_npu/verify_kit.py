"""Reference orchestration of the Kokoro NPU kit on CPU, compared with the original generator.

usage: verify_kit.py <kit_dir containing model.onnx link> [chunk_frames]
The original model draws random harmonic noise, so the reference reuses this run's pre outputs and
feeds them to the untouched generator subgraph extracted from model.onnx.
"""
import json, os, sys, time
import numpy as np
import onnx, onnxruntime as ort
sys.path.insert(0, os.path.dirname(__file__))
from build_kit import extract, GEN
from onnx import helper, TensorProto

kit = sys.argv[1]; frames = int(sys.argv[2]) if len(sys.argv) > 2 else 32
man = json.load(open(os.path.join(kit, "kit.json")))
o = ort.SessionOptions(); o.intra_op_num_threads = 8; o.log_severity_level = 3
S = lambda f: ort.InferenceSession(os.path.join(kit, f), o, providers=["CPUExecutionProvider"])
pre, up0, up1, post = S("pre.onnx"), S("up0.onnx"), S("up1.onnx"), S("post.onnx")
nup0, nup1, npost, tail = S("nup0.onnx"), S("nup1.onnx"), S("npost.onnx"), S("tail.onnx")
segs = {(s["stage"], s["block"], s["dilation"], s["half"]): (S(s["file"]), s) for s in man["segments"]}
H = man["halo"]; EPS = man["eps"]; CH = [frames * 20, frames * 120]  # samples per frame per stage

A = json.load(open(os.path.join(os.path.dirname(kit), "config.json")))["vocab"] if os.path.exists(os.path.join(os.path.dirname(kit), "config.json")) else json.load(open("/tmp/mytts-kokoro-assets/config.json"))["vocab"]
ipa = "kəɡdˈa pˈojɪzd nəkənʲˈɛʦ əstənəvʲˈilsʲə, nə pʲɪrˈonʲɪ ʊʒˈɛ nʲɪkəvˈo nʲɪ bˈɨlə. " * 2
ids = [0] + [A[c] for c in ipa if c in A] + [0]
pack = np.fromfile("/tmp/mytts-kokoro-assets/sveta.bin", dtype=np.float32).reshape(510, 256)
feed = {"input_ids": np.array([ids], np.int64), "style": pack[len(ids) - 3][None, :], "speed": np.array([1.0], np.float32)}
xin, nc0, nc1, style128, P, Q = pre.run(None, feed)
calls = [0]


def seg_run(key, x, residual):
    sess, meta = segs[key]; C, L = x.shape[1], x.shape[2]
    mu = x[0].astype(np.float64).mean(1); var = x[0].astype(np.float64).var(1); sig = np.sqrt(var + EPS)
    p = P[meta["coef"]:meta["coef"] + C].astype(np.float64); q = Q[meta["coef"]:meta["coef"] + C].astype(np.float64)
    a = (p / sig).astype(np.float32).reshape(1, C, 1); b = (q - p * mu / sig).astype(np.float32).reshape(1, C, 1)
    ch = CH[meta["stage"]]; W = ch + 2 * H; out = np.empty_like(x)
    for start in range(0, L, ch):
        lo = start - H; X = np.zeros((1, C, W), np.float32); M = np.zeros((1, 1, W), np.float32)
        s0, s1 = max(lo, 0), min(lo + W, L); X[:, :, s0 - lo:s1 - lo] = x[:, :, s0:s1]; M[:, :, s0 - lo:s1 - lo] = 1
        f = {"X": X, "A": a, "B": b, "M": M}
        if residual is not None:
            R = np.zeros((1, C, W), np.float32); R[:, :, s0 - lo:s1 - lo] = residual[:, :, s0:s1]; f["R"] = R
        Y = sess.run(None, f)[0]; n = min(ch, L - start); out[:, :, start:start + n] = Y[:, :, H:H + n]; calls[0] += 1
    return out


def resblock(stage, blk, x):
    for j in range(3):
        y = seg_run((stage, blk, j, 1), x, None)
        x = seg_run((stage, blk, j, 2), y, x)
    return x


def chunked(sess, x, core, h, scale, cout, out_len, shift):
    """Chunk an op that maps input length L to scale*L (ConvTranspose) or L (conv) with a halo."""
    C, L = x.shape[1], x.shape[2]; w = core + 2 * h; out = np.zeros((1, cout, out_len), np.float32)
    for start in range(0, L, core):
        lo = start - h; X = np.zeros((1, C, w), np.float32); s0, s1 = max(lo, 0), min(lo + w, L)
        X[:, :, s0 - lo:s1 - lo] = x[:, :, s0:s1]; Y = sess.run(None, {"X": X})[0]; calls[0] += 1
        n = min(core, L - start) * scale
        out[:, :, shift + start * scale:shift + start * scale + n] = Y[:, :, scale * h:scale * h + n]
    return out


t = time.perf_counter()
blocks = man["stages"]
x = up0.run(None, {"x": xin})[0] + resblock(0, blocks[0]["blocks"][0], nc0)
xs = sum(resblock(0, b, x) for b in blocks[0]["blocks"][1:]) / 3
x = up1.run(None, {"x": xs})[0] + resblock(1, blocks[1]["blocks"][0], nc1)
xs = sum(resblock(1, b, x) for b in blocks[1]["blocks"][1:]) / 3
wave = post.run(None, {"x": xs})[0]
dt = time.perf_counter() - t
# all-NPU glue path: chunked upsamplers + conv_post, CPU iSTFT tail only
g = man["glue"]; L0 = nc0.shape[2]; L1 = nc1.shape[2]
u0 = chunked(nup0, xin, 2 * frames, 2, g["nup0"]["scale"], 256, L0, 0)
x = u0 + resblock(0, blocks[0]["blocks"][0], nc0)
xs = sum(resblock(0, b, x) for b in blocks[0]["blocks"][1:]) / 3
u1 = chunked(nup1, xs, CH[0], 2, g["nup1"]["scale"], 128, L1, 1); u1[:, :, 0] = u1[:, :, 2]
x = u1 + resblock(1, blocks[1]["blocks"][0], nc1)
xs = sum(resblock(1, b, x) for b in blocks[1]["blocks"][1:]) / 3
pc = chunked(npost, xs, CH[1], H, 1, 22, L1, 0)
wave2 = tail.run(None, {"x": pc})[0]

# reference: the untouched generator from model.onnx on the same pre outputs
model = onnx.load(os.path.join(kit, man["model"]))
names = {"/decoder/decode.3/Mul_output_0": "xin", GEN + "noise_convs.0/Conv_output_0": "nc0", GEN + "noise_convs.1/Conv_output_0": "nc1", "/Slice_2_output_0": "style128"}
nodes, inits, gin = extract(model, list(names), ["waveform"], {k: (TensorProto.FLOAT, None) for k in names})
ref_m = helper.make_model(helper.make_graph(nodes, "ref", gin, [helper.make_tensor_value_info("waveform", TensorProto.FLOAT, None)], initializer=inits), opset_imports=model.opset_import, ir_version=model.ir_version)
ref = ort.InferenceSession(ref_m.SerializeToString(), o, providers=["CPUExecutionProvider"]).run(None, dict(zip(names, [xin, nc0, nc1, style128])))[0]
r, w = ref.ravel(), wave.ravel(); n = min(len(r), len(w))
snr = 10 * np.log10((r[:n] ** 2).sum() / max(((r[:n] - w[:n]) ** 2).sum(), 1e-30))
w2 = wave2.ravel()[:n]; snr2 = 10 * np.log10((r[:n] ** 2).sum() / max(((r[:n] - w2) ** 2).sum(), 1e-30))
def lsd(a, b):
    fr = lambda x: 20 * np.log10(np.abs(np.fft.rfft(np.lib.stride_tricks.sliding_window_view(x, 1024)[::256] * np.hanning(1024))) + 1e-5)
    return float(np.sqrt(((fr(a) - fr(b)) ** 2).mean(1)).mean())
print(f"NPU-glue path: SNR={snr2:.1f} dB maxAbs={np.abs(r[:n]-w2).max():.2e} LSD={lsd(r[:n], w2):.2f} dB (Kokoro's own run-to-run noise ~2.2 dB)")
print(f"frames/chunk={frames} T={xin.shape[2]} samples={len(w)} ref={len(r)} calls={calls[0]} orchestrated {dt*1000:.0f} ms  SNR={snr:.1f} dB maxAbs={np.abs(r[:n]-w[:n]).max():.2e}")
