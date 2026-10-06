import json, sys, unicodedata, numpy as np, onnxruntime as ort
S = sys.argv[1]; D = S + "/tera"; idx = np.array(json.load(open(f"{D}/unicode_indexer.json")))
o = ort.SessionOptions(); o.intra_op_num_threads = 8; o.log_severity_level = 3
te = ort.InferenceSession(f"{D}/text_encoder.onnx", o, providers=["CPUExecutionProvider"])
orig = ort.InferenceSession(f"{D}/sampler_distilled_cfg3_8step.onnx", o, providers=["CPUExecutionProvider"])
st = ort.InferenceSession(S + "/npu/tera_step.onnx", o, providers=["CPUExecutionProvider"])
ins = {i.name: i.shape for i in st.get_inputs()}
tsh = [ins[f"t{i}"] for i in range(4)]; sizes = [int(np.prod(s)) for s in tsh]
table = np.fromfile(S + "/npu/tera_step_time.bin", dtype=np.float32).reshape(8, -1)
ttl = np.load(f"{D}/ru_m1_style_ttl.npy").astype(np.float32).reshape(1, 50, 256)
p = unicodedata.normalize("NFKD", "<ru>Когда п+оезд наконец остановился, на перр+оне уже никого не было.</ru>")
ids = np.array([[idx[ord(c)] for c in p if ord(c) < len(idx) and idx[ord(c)] >= 0]], np.int64); L = ids.shape[1]; tm = np.ones((1, 1, L), np.float32)
emb = te.run(None, {"text_ids": ids, "style_ttl": ttl, "text_mask": tm})[0]
T = 61; noise = np.random.RandomState(3).standard_normal((1, 144, T)).astype(np.float32)
ref = orig.run(None, {"initial_latent": noise, "text_emb": emb, "style_ttl": ttl, "latent_mask": np.ones((1, 1, T), np.float32), "text_mask": tm, "guidance": np.array([3], np.float32)})[0]
for N, LL in ((96, 96), (128, 128)):
    x = np.zeros((1, 144, N), np.float32); x[:, :, :T] = noise; m = np.zeros((1, 1, N), np.float32); m[:, :, :T] = 1
    ls = np.zeros((1, 1, N), np.float32); ls[0, 0, T - 1] = 1
    e = np.zeros((1, 256, LL), np.float32); e[:, :, :L] = emb; tmm = np.zeros((1, 1, LL), np.float32); tmm[:, :, :L] = 1
    for s in range(8):
        parts = np.split(table[s], np.cumsum(sizes)[:-1])
        f = {"noisy_latent": x, "text_emb": e, "style_ttl": ttl, "latent_mask": m, "text_mask": tmm, "last_sel": ls, "guidance": np.array([3], np.float32)}
        f.update({f"t{i}": parts[i].reshape(tsh[i]) for i in range(4)})
        x = st.run(None, {k: v for k, v in f.items() if k in ins})[0]
    y = x[:, :, :T]; print(f"frames {T}->{N}, text {L}->{LL}: SNR={10*np.log10((ref**2).sum()/max(((ref-y)**2).sum(),1e-30)):.1f} dB")
