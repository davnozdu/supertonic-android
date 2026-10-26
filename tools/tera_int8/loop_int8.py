"""int8 Tera sampler that keeps its Loop: the body (self-contained, 2003 nodes) is taken out as a model,
1x1 Conv/Gemm -> MatMul (tera_graph_tools), MatMul quantized to int8 exactly as the fork does, then put back.
Same weights quantization as the unrolled build, ~8x fewer nodes -> fast session creation on the phone."""
import sys, copy, onnx
from onnx import helper
sys.path.insert(0, sys.argv[1])
import tera_graph_tools as tg
src, work, out = sys.argv[2], sys.argv[3], sys.argv[4]
m = onnx.load(src)
loop = [n for n in m.graph.node if n.op_type == "Loop"][0]
attr = next(a for a in loop.attribute if a.name == "body")
body = attr.g
bm = helper.make_model(copy.deepcopy(body), opset_imports=m.opset_import, ir_version=m.ir_version)
bm, conv = tg.conv1x1_to_matmul(bm)
gemm = tg.gemm_to_matmul(bm)
tg.drop_unused_initializers(bm)
try: bm = onnx.shape_inference.infer_shapes(bm)
except Exception as e: print("shape inference:", e)
onnx.save(bm, f"{work}/body_prep.onnx")
excluded = tg.quantize(f"{work}/body_prep.onnx", f"{work}/body_int8.onnx")
q = onnx.load(f"{work}/body_int8.onnx")
print("conv->matmul", conv, "gemm", gemm, "excluded", len(excluded), "body nodes", len(q.graph.node))
assert [i.name for i in q.graph.input] == [i.name for i in body.input]
assert [o.name for o in q.graph.output] == [o.name for o in body.output]
newbody = copy.deepcopy(q.graph); newbody.name = body.name
del newbody.value_info[:]  # let the runtime infer inner shapes
attr.g.CopyFrom(newbody)
# the quantized body may use ops from the com.microsoft domain
have = {o.domain for o in m.opset_import}
for o in q.opset_import:
    if o.domain not in have: m.opset_import.append(o)
onnx.save(m, out)
print("saved", out)
