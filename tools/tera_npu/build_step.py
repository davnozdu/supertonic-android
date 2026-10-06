"""Builds the Tera sampler NPU kit: ONE denoising step of sampler_distilled_cfg3_8step.onnx as a
static-shape friendly graph that references the pinned sampler file's weights (no copies).

Changes against the 8-step ONNX Loop body (all exact, verified by verify_step.py):
  * current_step/total_step: the step-time encoder (sin/cos of step * freq + MLP) overflows in FP16 on the
    HTP, and it depends on the step only -> its four per-stage outputs are precomputed in FP32 (time.bin)
    and become inputs t0..t3;
  * edge Pads replicate the last VALID frame (input last_sel, one-hot at frame T-1), so padding frames
    with latent_mask = 0 is exact (shared fixed-length buckets);
  * Softplus (no HTP kernel) -> relu(x) + log(1 + exp(-|x|)).

usage: build_step.py <sampler_distilled_cfg3_8step.onnx> <out_dir>
"""
import json, os, sys
import numpy as np
import onnx, onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from edgefix import fix


def _varint(b, i):
    r = s = 0
    while True:
        c = b[i]; i += 1; r |= (c & 0x7F) << s; s += 7
        if c < 0x80: return r, i


def _fields(b, start, end):
    i = start
    while i < end:
        key, i = _varint(b, i); f, t = key >> 3, key & 7
        if t == 0: _, i = _varint(b, i); continue
        if t == 1: i += 8; continue
        if t == 5: i += 4; continue
        if t != 2: raise ValueError(f"wire type {t}")
        n, i = _varint(b, i); yield f, i, i + n; i += n


def _graph_offsets(b, s, e, out):
    """GraphProto: initializer = 5, node = 1 -> attribute = 5 -> g = 6 / graphs = 11 (recursive)."""
    for f, fs, fe in _fields(b, s, e):
        if f == 5:
            name = raw = None
            for tf, ts, te in _fields(b, fs, fe):
                if tf == 8: name = b[ts:te].decode()
                elif tf == 9: raw = (ts, te - ts)
            if name and raw: out[name] = raw
        elif f == 1:
            for nf, ns, ne in _fields(b, fs, fe):
                if nf != 5: continue
                for af, as_, ae in _fields(b, ns, ne):
                    if af in (6, 11): _graph_offsets(b, as_, ae, out)


def raw_offsets(path):
    b = open(path, "rb").read(); out = {}
    for f, s, e in _fields(b, 0, len(b)):
        if f == 7: _graph_offsets(b, s, e, out)
    return out


def build(src):
    m = onnx.load(src); g = m.graph
    body = next(a.g for a in g.node[0].attribute if a.name == "body")
    nodes = [n for n in body.node if not (n.op_type == "Cast" and n.input[0] == "iteration")
             and not (n.op_type == "Expand" and n.output[0] == "current_step")]
    ins = [helper.make_tensor_value_info("noisy_latent", TensorProto.FLOAT, ["batch", 144, "generated_latent_length"]),
           helper.make_tensor_value_info("text_emb", TensorProto.FLOAT, ["batch", 256, "text_length"]),
           helper.make_tensor_value_info("style_ttl", TensorProto.FLOAT, ["batch", 50, 256]),
           helper.make_tensor_value_info("latent_mask", TensorProto.FLOAT, ["batch", 1, "generated_latent_length"]),
           helper.make_tensor_value_info("text_mask", TensorProto.FLOAT, ["batch", 1, "text_length"]),
           helper.make_tensor_value_info("guidance", TensorProto.FLOAT, ["batch"]),
           helper.make_tensor_value_info("current_step", TensorProto.FLOAT, ["batch"])]
    producer = {o: i for i, n in enumerate(nodes) for o in n.output}
    keep = set(); stack = ["output"]
    while stack:
        t = stack.pop()
        if t in producer and producer[t] not in keep:
            keep.add(producer[t]); stack.extend(x for x in nodes[producer[t]].input if x)
    nodes = [n for i, n in enumerate(nodes) if i in keep]
    used = {x for n in nodes for x in n.input}
    graph = helper.make_graph(nodes, "tera_step", [i for i in ins if i.name in used],
                              [helper.make_tensor_value_info("output", TensorProto.FLOAT, ["batch", 144, "generated_latent_length"])],
                              initializer=list(body.initializer))
    step = helper.make_model(graph, opset_imports=m.opset_import, ir_version=m.ir_version)
    step, pads = fix(step)
    out = []; c = 0
    step.graph.initializer.append(numpy_helper.from_array(np.array(1.0, np.float32), "_sp_one"))
    for n in step.graph.node:
        if n.op_type != "Softplus": out.append(n); continue
        x = n.input[0]; p = f"_sp{c}_"; c += 1
        out += [helper.make_node("Relu", [x], [p + "r"]), helper.make_node("Abs", [x], [p + "a"]), helper.make_node("Neg", [p + "a"], [p + "n"]),
                helper.make_node("Exp", [p + "n"], [p + "e"]), helper.make_node("Add", [p + "e", "_sp_one"], [p + "1e"]),
                helper.make_node("Log", [p + "1e"], [p + "l"]), helper.make_node("Add", [p + "r", p + "l"], list(n.output), name=n.name)]
    del step.graph.node[:]; step.graph.node.extend(out)
    # Step-time encoder -> FP32 table.
    g = step.graph
    total = next(n for n in g.node if n.output[0] == "total_step")
    steps = int(numpy_helper.to_array(next(a.t for a in total.attribute if a.name == "value")).ravel()[0])
    const = {i.name for i in g.initializer} | {n.output[0] for n in g.node if n.op_type == "Constant"} | {"total_step"}
    pure = {"current_step"}
    for n in g.node:
        xs = [x for x in n.input if x]
        if n.op_type != "Constant" and xs and all(x in pure or x in const for x in xs) and any(x in pure for x in xs): pure.update(n.output)
    front = sorted({next(x for x in n.input if x in pure) for n in g.node if not set(n.output) <= pure and any(x in pure for x in n.input)})
    probe = onnx.ModelProto(); probe.CopyFrom(step)
    del probe.graph.output[:]; probe.graph.output.extend([helper.make_empty_tensor_value_info(x) for x in front])
    o = ort.SessionOptions(); o.log_severity_level = 3
    sess = ort.InferenceSession(probe.SerializeToString(), o, providers=["CPUExecutionProvider"])
    need = {i.name for i in sess.get_inputs()}
    table = []
    for st in range(steps):
        f = {"current_step": np.array([st], np.float32), "guidance": np.array([3], np.float32),
             "noisy_latent": np.zeros((1, 144, 4), np.float32), "text_emb": np.zeros((1, 256, 4), np.float32),
             "style_ttl": np.zeros((1, 50, 256), np.float32), "latent_mask": np.ones((1, 1, 4), np.float32),
             "text_mask": np.ones((1, 1, 4), np.float32), "last_sel": np.eye(4, dtype=np.float32)[3].reshape(1, 1, 4)}
        table.append(sess.run(None, {k: v for k, v in f.items() if k in need}))
    shapes = [list(t.shape) for t in table[0]]
    ren = {x: f"t{i}" for i, x in enumerate(front)}
    kept = [n for n in g.node if not set(n.output) <= pure]
    for n in kept: n.input[:] = [ren.get(x, x) for x in n.input]
    del g.node[:]; g.node.extend(kept)
    alive = {x for n in g.node for x in n.input}
    newin = [i for i in g.input if i.name in alive] + [helper.make_tensor_value_info(f"t{i}", TensorProto.FLOAT, sh) for i, sh in enumerate(shapes)]
    del g.input[:]; g.input.extend(newin)
    inits = [i for i in g.initializer if i.name in alive]; del g.initializer[:]; g.initializer.extend(inits)
    return step, np.concatenate([np.concatenate([t.ravel() for t in row]) for row in table]).astype(np.float32), shapes, steps, pads


def main(src, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    step, table, shapes, steps, pads = build(src)
    offsets = raw_offsets(src); location = os.path.basename(src)
    original = {}
    m = onnx.load(src, load_external_data=False)
    for a in m.graph.node[0].attribute:
        if a.name == "body": original = {t.name: t for t in a.g.initializer}
    for t in step.graph.initializer:
        if t.name.startswith("_"): continue
        off = offsets.get(t.name); src_t = original.get(t.name)
        if off is None and src_t is not None and not src_t.raw_data and t.ByteSize() < 4096: continue
        if off is None or src_t is None or src_t.raw_data != t.raw_data: raise ValueError(f"no raw bytes for {t.name}")
        t.ClearField("raw_data"); t.data_location = TensorProto.EXTERNAL
        for k, v in (("location", location), ("offset", str(off[0])), ("length", str(off[1]))):
            e = t.external_data.add(); e.key = k; e.value = v
    onnx.save(step, os.path.join(out_dir, "step.onnx"))
    table.tofile(os.path.join(out_dir, "time.bin"))
    json.dump({"version": 1, "model": location, "modelSize": os.path.getsize(src), "steps": steps, "timeShapes": shapes,
               "edgePads": pads}, open(os.path.join(out_dir, "kit.json"), "w"), indent=1)
    print("tera step kit", out_dir, "nodes", len(step.graph.node), "steps", steps, "time", shapes)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
