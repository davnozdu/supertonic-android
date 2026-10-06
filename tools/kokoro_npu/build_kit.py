"""Builds the Kokoro-RU NPU kit from a pinned full-precision upstream model.

The iSTFTNet generator is ~90% of Kokoro's time. Its AdaIN InstanceNorms use statistics over the whole
phrase, so it cannot simply run in windows. The kit splits it at every norm:

  pre.onnx   (CPU)  text -> generator input, noise branches and per-norm AdaIN coefficients P, Q
  seg_*.onnx (NPU)  y = A*x + B -> Snake -> mask -> Conv [+ residual], run in fixed chunks with a halo
  up0/up1/post.onnx (CPU)  upsampling and the iSTFT head between the two generator stages

The app computes per-channel mean/var of every norm input over the whole phrase and sets
A = P/sigma, B = Q - P*mu/sigma, which reproduces the original AdaIN exactly (up to fp16 on the NPU).

Weights are not copied: every initializer is an ONNX external-data reference into the original model file
(byte offsets of its raw_data). The kit is a few MB of graph structure and only matches the pinned model.

usage: build_kit.py <model.onnx> <out_dir>
"""
import json, os, sys
import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

GEN = "/decoder/generator/"
STAGES = [["noise_res.0", "resblocks.0", "resblocks.1", "resblocks.2"],
          ["noise_res.1", "resblocks.3", "resblocks.4", "resblocks.5"]]
CHANNELS = [256, 128]
HALO = 32  # >= largest conv radius (kernel 11, dilation 5 -> 25)


# ---------- protobuf walk: byte offset of every initializer's raw_data ----------
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


def raw_offsets(path):
    b = open(path, "rb").read(); out = {}
    for f, s, e in _fields(b, 0, len(b)):
        if f != 7: continue
        for gf, gs, ge in _fields(b, s, e):
            if gf != 5: continue
            name = None; raw = None
            for tf, ts, te in _fields(b, gs, ge):
                if tf == 8: name = b[ts:te].decode()
                elif tf == 9: raw = (ts, te - ts)
            if name and raw: out[name] = raw
    return out


# ---------- subgraph extraction that follows implicit Loop/If inputs ----------
def _sub_refs(graph):
    defined = {i.name for i in graph.input} | {i.name for i in graph.initializer} | {o for n in graph.node for o in n.output}
    refs = set()
    for n in graph.node:
        refs |= {x for x in n.input if x and x not in defined}
        for a in n.attribute:
            for g in ([a.g] if a.type == onnx.AttributeProto.GRAPH else list(a.graphs)):
                refs |= {x for x in _sub_refs(g) if x not in defined}
    return refs


def _needs(n):
    s = {x for x in n.input if x}
    for a in n.attribute:
        for g in ([a.g] if a.type == onnx.AttributeProto.GRAPH else list(a.graphs)): s |= _sub_refs(g)
    return s


def extract(model, inputs, outputs, input_types):
    g = model.graph
    producer = {o: i for i, n in enumerate(g.node) for o in n.output}
    keep = set(); stack = list(outputs); seen = set(inputs)
    while stack:
        t = stack.pop()
        if t in seen: continue
        seen.add(t)
        if t in producer and producer[t] not in keep:
            keep.add(producer[t]); stack.extend(_needs(g.node[producer[t]]))
    nodes = [g.node[i] for i in sorted(keep)]
    used = set().union(*[_needs(n) for n in nodes]) | set(outputs)
    missing = [x for x in used if x not in producer or producer[x] not in keep]
    missing = [x for x in missing if x not in inputs and x not in {i.name for i in g.initializer} and x not in {i.name for i in g.input}]
    if missing: raise ValueError(f"unresolved tensors {missing[:5]}")
    extra_inputs = [i.name for i in g.input if i.name in used and i.name not in inputs]
    if extra_inputs and input_types: raise ValueError(f"subgraph also needs model inputs {extra_inputs}")
    inits = [x for x in g.initializer if x.name in used]
    gin = [i for i in g.input if i.name in inputs] + [helper.make_tensor_value_info(n, t, s) for n, (t, s) in input_types.items()]
    return nodes, inits, gin


def node_by_name(model):
    return {n.name: n for n in model.graph.node}


# ---------- kit pieces ----------
def adain_list():
    out = []
    for stage, blocks in enumerate(STAGES):
        for blk in blocks:
            for j in range(3):
                for half in (1, 2):
                    out.append((stage, blk, j, half))
    return out


def build_pre(model, nodes):
    """input_ids/style/speed -> xin, nc0, nc1, style128, P, Q."""
    extra = []; p_parts = []; q_parts = []
    flat = numpy_helper.from_array(np.array([-1], dtype=np.int64), "_kit_flat")
    for k, (stage, blk, j, half) in enumerate(adain_list()):
        a = f"{GEN}{blk}/adain{half}.{j}/"
        norm = nodes[a + "norm/InstanceNormalization"]
        g1 = nodes[a + "Add_1"].output[0]; beta = nodes[a + "Slice_1"].output[0]
        w, b = norm.input[1], norm.input[2]
        def flatten(x, tag):
            y = f"_kit{k}_{tag}"; extra.append(helper.make_node("Reshape", [x, "_kit_flat"], [y])); return y
        G1, W, B, BE = flatten(g1, "g"), flatten(w, "w"), flatten(b, "b"), flatten(beta, "be")
        extra += [helper.make_node("Mul", [G1, W], [f"_kit{k}_P"]),
                  helper.make_node("Mul", [G1, B], [f"_kit{k}_gb"]),
                  helper.make_node("Add", [f"_kit{k}_gb", BE], [f"_kit{k}_Q"])]
        p_parts.append(f"_kit{k}_P"); q_parts.append(f"_kit{k}_Q")
    extra += [helper.make_node("Concat", p_parts, ["P"], axis=0), helper.make_node("Concat", q_parts, ["Q"], axis=0)]
    targets = {"xin": "/decoder/decode.3/Mul_output_0", "nc0": GEN + "noise_convs.0/Conv_output_0",
               "nc1": GEN + "noise_convs.1/Conv_output_0", "style128": "/Slice_2_output_0"}
    needed = list(targets.values()) + sorted({x for n in extra for x in n.input if not x.startswith("_kit")})
    base, inits, gin = extract(model, ["input_ids", "style", "speed"], needed, {})
    extra += [helper.make_node("Identity", [v], [k]) for k, v in targets.items()]
    outs = [helper.make_tensor_value_info(n, TensorProto.FLOAT, None) for n in list(targets) + ["P", "Q"]]
    graph = helper.make_graph(base + extra, "kokoro_pre", gin, outs, initializer=inits + [flat])
    return graph


def build_glue(model, name, inp, out_tensor, out_name, channels):
    nodes, inits, gin = extract(model, [inp], [out_tensor], {"x": (TensorProto.FLOAT, [1, channels, "len"])})
    nodes = [onnx.NodeProto.FromString(n.SerializeToString()) for n in nodes]
    for n in nodes:
        n.input[:] = ["x" if x == inp else x for x in n.input]
        n.output[:] = [out_name if x == out_tensor else x for x in n.output]
    return helper.make_graph(nodes, name, gin, [helper.make_tensor_value_info(out_name, TensorProto.FLOAT, None)], initializer=inits)


def build_segment(model, nodes, stage, blk, j, half):
    """X*A+B -> Snake(alpha) -> *M -> Conv [+R]: everything between two AdaIN norms."""
    C = CHANNELS[stage]; pre = f"{GEN}{blk}/"
    conv = nodes.get(f"{pre}convs{half}.{j}/Conv")
    # alpha: the Snake Mul that feeds this conv's Sin
    alpha = f"kmodel.decoder.generator.{blk}.alpha{half}.{j}"
    ins = [helper.make_tensor_value_info("X", TensorProto.FLOAT, [1, C, "w"]),
           helper.make_tensor_value_info("A", TensorProto.FLOAT, [1, C, 1]),
           helper.make_tensor_value_info("B", TensorProto.FLOAT, [1, C, 1]),
           helper.make_tensor_value_info("M", TensorProto.FLOAT, [1, 1, "w"])]
    ns = [helper.make_node("Mul", ["X", "A"], ["xa"]), helper.make_node("Add", ["xa", "B"], ["y"]),
          helper.make_node("Mul", [alpha, "y"], ["ay"]), helper.make_node("Sin", ["ay"], ["s"]),
          helper.make_node("Mul", ["s", "s"], ["s2"]),  # HTP mis-evaluates Pow(x, 2)
          helper.make_node("Reciprocal", [alpha], ["ra"]), helper.make_node("Mul", ["ra", "s2"], ["t"]),
          helper.make_node("Add", ["y", "t"], ["sn"]), helper.make_node("Mul", ["sn", "M"], ["snm"])]
    if conv is not None:
        weights = list(conv.input[1:]); attrs = conv.attribute
    else:
        # Q8 package: uint8 weights + per-tensor scale/zero point (ConvInteger). Dequantize in the graph
        # with plain ops on initializers; ORT constant-folds them, the NPU then runs the conv in FP16.
        cq = nodes[f"{pre}convs{half}.{j}/Conv_quant"]; attrs = cq.attribute
        scale = nodes[f"{pre}convs{half}.{j}/Conv_quant_scales_mul"].input[1]
        producer = {o: n for n in model.graph.node for o in n.output}
        bias = producer[nodes[f"{pre}convs{half}.{j}/Conv_output_0_bias_add"].input[1]].input[0]
        ns += [helper.make_node("Cast", [cq.input[1]], ["wq"], to=TensorProto.FLOAT),
               helper.make_node("Cast", [cq.input[3]], ["wz"], to=TensorProto.FLOAT),
               helper.make_node("Sub", ["wq", "wz"], ["wc"]), helper.make_node("Mul", ["wc", scale], ["wd"])]
        weights = ["wd", bias]
    ns.append(helper.make_node("Conv", ["snm"] + weights, ["c"] if half == 2 else ["Y"], name="conv",
                               **{a.name: helper.get_attribute_value(a) for a in attrs}))
    if half == 2:
        ins.append(helper.make_tensor_value_info("R", TensorProto.FLOAT, [1, C, "w"]))
        ns.append(helper.make_node("Add", ["c", "R"], ["Y"]))
    used = {x for n in ns for x in n.input}
    inits = [i for i in model.graph.initializer if i.name in used]
    if alpha not in {i.name for i in inits} or len(inits) < 3: raise ValueError(f"segment weights missing for {blk} {half}.{j}")
    return helper.make_graph(ns, f"seg_{blk}_{half}_{j}", ins, [helper.make_tensor_value_info("Y", TensorProto.FLOAT, [1, C, "w"])], initializer=inits)


def build_up(model, nodes, name, convt, cin, cout, alpha):
    """LeakyRelu as max(x, a*x) (exact; HTP mis-evaluates LeakyRelu(0.01)) -> ConvTranspose, chunked on the NPU."""
    ct = nodes[convt]
    a = numpy_helper.from_array(np.array(alpha, dtype=np.float32), f"_kit_{name}_alpha")
    ns = [helper.make_node("Mul", ["X", a.name], ["ax"]), helper.make_node("Max", ["X", "ax"], ["lx"]),
          helper.make_node("ConvTranspose", ["lx"] + list(ct.input[1:]), ["Y"], name="up",
                           **{x.name: helper.get_attribute_value(x) for x in ct.attribute})]
    inits = [i for i in model.graph.initializer if i.name in set(ct.input[1:])] + [a]
    stride = [helper.get_attribute_value(x) for x in ct.attribute if x.name == "strides"][0][0]
    return helper.make_graph(ns, name, [helper.make_tensor_value_info("X", TensorProto.FLOAT, [1, cin, "w"])],
                             [helper.make_tensor_value_info("Y", TensorProto.FLOAT, [1, cout, None])], initializer=inits), stride


def build_post_conv(model, nodes):
    conv = nodes[GEN + "conv_post/Conv"]
    a = numpy_helper.from_array(np.array(0.01, dtype=np.float32), "_kit_post_alpha")
    ns = [helper.make_node("Mul", ["X", a.name], ["ax"]), helper.make_node("Max", ["X", "ax"], ["lx"]),
          helper.make_node("Conv", ["lx"] + list(conv.input[1:]), ["Y"], name="post",
                           **{x.name: helper.get_attribute_value(x) for x in conv.attribute})]
    inits = [i for i in model.graph.initializer if i.name in set(conv.input[1:])] + [a]
    return helper.make_graph(ns, "npost", [helper.make_tensor_value_info("X", TensorProto.FLOAT, [1, 128, "w"])],
                             [helper.make_tensor_value_info("Y", TensorProto.FLOAT, [1, 22, "w"])], initializer=inits)


def externalize(graph, offsets, location, original):
    """Point every original initializer at its bytes inside the model file; keep kit constants inline."""
    for t in graph.initializer:
        if t.name.startswith("_kit"): continue
        off = offsets.get(t.name)
        src = original.get(t.name)
        if off is None and src is not None and not src.raw_data and t.ByteSize() < 4096: continue  # tiny typed scalar: inline
        if off is None or src is None or src.raw_data != t.raw_data: raise ValueError(f"no raw bytes for {t.name}")
        t.ClearField("raw_data"); t.data_location = TensorProto.EXTERNAL
        del t.external_data[:]
        for k, v in (("location", location), ("offset", str(off[0])), ("length", str(off[1]))):
            e = t.external_data.add(); e.key = k; e.value = v


def main(model_path, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    location = os.path.basename(model_path)
    model = onnx.load(model_path)
    offsets = raw_offsets(model_path)
    original = {t.name: t for t in model.graph.initializer}
    nodes = node_by_name(model)
    opset = model.opset_import; ir = model.ir_version
    def save(graph, name):
        externalize(graph, offsets, location, original)
        m = helper.make_model(graph, opset_imports=opset, ir_version=ir)
        onnx.save(m, os.path.join(out_dir, name))
    save(build_pre(model, nodes), "pre.onnx")
    save(build_glue(model, "up0", "/decoder/decode.3/Mul_output_0", GEN + "ups.0/ConvTranspose_output_0", "u0", 512), "up0.onnx")
    save(build_glue(model, "up1", GEN + "Div_1_output_0", GEN + "reflection_pad/Pad_output_0", "u1", 256), "up1.onnx")
    save(build_glue(model, "post", GEN + "Div_2_output_0", "waveform", "waveform", 128), "post.onnx")
    # NPU glue: both upsamplers and conv_post in chunks; only the iSTFT tail stays on the CPU.
    g, s0 = build_up(model, nodes, "nup0", GEN + "ups.0/ConvTranspose", 512, 256, 0.1); save(g, "nup0.onnx")
    g, s1 = build_up(model, nodes, "nup1", GEN + "ups.1/ConvTranspose", 256, 128, 0.1); save(g, "nup1.onnx")
    save(build_post_conv(model, nodes), "npost.onnx")
    save(build_glue(model, "tail", GEN + "conv_post/Conv_output_0", "waveform", "waveform", 22), "tail.onnx")
    segments = []
    for k, (stage, blk, j, half) in enumerate(adain_list()):
        file = f"seg_{stage}_{blk.replace('.', '')}_{half}_{j}.onnx"
        save(build_segment(model, nodes, stage, blk, j, half), file)
        segments.append({"file": file, "stage": stage, "block": blk, "dilation": j, "half": half, "channels": CHANNELS[stage]})
    offs = []; o = 0
    for stage, *_ in adain_list(): offs.append(o); o += CHANNELS[stage]
    manifest = {"version": 1, "model": location, "modelSize": os.path.getsize(model_path), "eps": 1e-5, "halo": HALO,
                "stages": [{"channels": CHANNELS[s], "blocks": STAGES[s]} for s in range(2)],
                "segments": [dict(s, coef=offs[i]) for i, s in enumerate(segments)], "coefSize": o,
                "glue": {"nup0": {"in": 512, "out": 256, "scale": s0, "halo": 2}, "nup1": {"in": 256, "out": 128, "scale": s1, "halo": 2},
                         "npost": {"in": 128, "out": 22, "scale": 1, "halo": HALO}}}
    json.dump(manifest, open(os.path.join(out_dir, "kit.json"), "w"), indent=1)
    print("kit", out_dir, "segments", len(segments), "coef", o)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
