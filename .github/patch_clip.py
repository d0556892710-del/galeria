#!/usr/bin/env python3
"""ONNX Runtime on Android has no ArgMax for 64-bit integers; the CLIP text model uses one to find the
end-of-text token. The token ids are small, so they are turned into floats first (exact) and ArgMax runs on those."""
import sys, numpy as np, onnx
from onnx import helper, TensorProto

mode = sys.argv[1]
if mode == "patch":
    src, dst = sys.argv[2], sys.argv[3]
    m = onnx.load(src)
    inf = onnx.shape_inference.infer_shapes(m)
    types = {v.name: v.type.tensor_type.elem_type for v in list(inf.graph.value_info) + list(inf.graph.input) + list(inf.graph.output)}
    nodes, patched = [], 0
    for n in m.graph.node:
        if n.op_type in ("ArgMax", "ArgMin") and types.get(n.input[0], TensorProto.INT64) == TensorProto.INT64:
            out = n.input[0] + "__float"
            nodes.append(helper.make_node("Cast", [n.input[0]], [out], to=TensorProto.FLOAT, name=(n.name or "argmax") + "__cast"))
            n.input[0] = out; patched += 1
        nodes.append(n)
    del m.graph.node[:]; m.graph.node.extend(nodes)
    onnx.checker.check_model(m)
    onnx.save(m, dst)
    print("patched ArgMax nodes:", patched)
else:
    # run: model ids.npy out.npy
    import onnxruntime as ort
    print("onnxruntime", ort.__version__)
    s = ort.InferenceSession(sys.argv[2], providers=["CPUExecutionProvider"])
    ids = np.load(sys.argv[3])
    out = s.run(None, {s.get_inputs()[0].name: ids})[0]
    np.save(sys.argv[4], out); print("ok", out.shape)
