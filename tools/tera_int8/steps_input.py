"""Make the Tera sampler's step count an input: 'steps' (int64 [1]) drives the Loop trip count and the Euler step 1/steps.
Without it the graph is the same; with steps=8 the output is identical to the fixed 8-step model."""
import sys, onnx, numpy as np
from onnx import helper, numpy_helper, TensorProto
src, out = sys.argv[1], sys.argv[2]
m = onnx.load(src); g = m.graph
loop = [n for n in g.node if n.op_type == "Loop"][0]
g.input.append(helper.make_tensor_value_info("steps", TensorProto.INT64, [1]))
# trip count: Loop wants a scalar int64
g.node.insert(0, helper.make_node("Squeeze", ["steps", "steps_axes"], ["steps_scalar"], name="steps_squeeze"))
g.initializer.append(numpy_helper.from_array(np.array([0], np.int64), "steps_axes"))
loop.input[0] = "steps_scalar"
old = [i for i in g.initializer if i.name == "sampler_trip_count"]
for i in old: g.initializer.remove(i)
g.node.insert(1, helper.make_node("Cast", ["steps"], ["steps_float"], to=TensorProto.FLOAT, name="steps_cast"))
body = next(a.g for a in loop.attribute if a.name == "body")
n = [x for x in body.node if x.output and x.output[0] == "total_step"][0]
idx = list(body.node).index(n)
body.node.remove(n)
# same shape as before (ConstantOfShape(guidance shape)), value from the outer 'steps_float'
body.node.insert(idx, helper.make_node("Expand", ["steps_float", "sampler_guidance_shape"], ["total_step"], name="total_step_expand"))
onnx.checker.check_model(m)
onnx.save(m, out); print("saved", out)
