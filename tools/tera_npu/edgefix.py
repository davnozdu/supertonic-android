"""Frame padding for the Tera sampler: edge Pads must replicate the last VALID frame, not the padded zeros.
Adds input last_sel [B,1,N] (one-hot at frame T-1). x_filled = x + E*(1-mask), E = sum_t x*last_sel."""
import sys, onnx, numpy as np
from onnx import helper, numpy_helper, TensorProto
def fix(m):
    g = m.graph; out = []; k = 0
    opset = next(o.version for o in m.opset_import if o.domain in ("", "ai.onnx"))
    g.input.append(helper.make_tensor_value_info("last_sel", TensorProto.FLOAT, ["batch", 1, "generated_latent_length"]))
    g.initializer.append(numpy_helper.from_array(np.array([2], np.int64), "_ef_axes"))
    g.initializer.append(numpy_helper.from_array(np.array(1.0, np.float32), "_ef_one"))
    out.append(helper.make_node("Sub", ["_ef_one", "latent_mask"], ["_ef_inv"]))
    for n in g.node:
        mode = [a.s for a in n.attribute if a.name == "mode"]
        if n.op_type == "Pad" and mode and mode[0] == b"edge":
            x = n.input[0]; p = f"_ef{k}_"; k += 1
            red = (helper.make_node("ReduceSum", [p + "xs", "_ef_axes"], [p + "e"], keepdims=1) if opset >= 13
                   else helper.make_node("ReduceSum", [p + "xs"], [p + "e"], axes=[2], keepdims=1))
            out += [helper.make_node("Mul", [x, "last_sel"], [p + "xs"]), red,
                    helper.make_node("Mul", [p + "e", "_ef_inv"], [p + "ei"]),
                    helper.make_node("Add", [x, p + "ei"], [p + "xf"])]
            n.input[0] = p + "xf"
        out.append(n)
    del g.node[:]; g.node.extend(out); return m, k
if __name__ == "__main__":
    m, k = fix(onnx.load(sys.argv[1])); onnx.save(m, sys.argv[2], save_as_external_data=False); print("fixed pads", k)
