import time, torch, numpy as np, onnxruntime as ort
torch.set_grad_enabled(False)
m = torch.jit.load("big-lama.pt", map_location="cpu").eval()
print("loaded", flush=True)
for S in (768, 1024):
    img = torch.rand(1, 3, S, S)
    mask = torch.zeros(1, 1, S, S); mask[:, :, S // 4: S // 2, S // 3: 2 * S // 3] = 1
    t = time.time(); ref = m(img, mask); print(S, "torch out", tuple(ref.shape), float(ref.min()), float(ref.max()), f"{time.time()-t:.1f}s", flush=True)
    name = f"lama_{S}.onnx"
    ok = False
    for kw in ({"opset_version": 17}, {"opset_version": 18}):
        try:
            torch.onnx.export(m, (img, mask), name, input_names=["image", "mask"], output_names=["output"], **kw)
            ok = True; print(S, "exported with", kw, flush=True); break
        except Exception as e:
            print(S, "export failed", kw, repr(e)[:600], flush=True)
    if not ok:
        try:
            ep = torch.onnx.export(m, (img, mask), dynamo=True)
            ep.save(name); ok = True; print(S, "exported with dynamo", flush=True)
        except Exception as e:
            print(S, "dynamo failed", repr(e)[:600], flush=True)
    if not ok: continue
    s = ort.InferenceSession(name, providers=["CPUExecutionProvider"])
    print(S, "IN", [(i.name, i.shape) for i in s.get_inputs()], "OUT", [(o.name, o.shape) for o in s.get_outputs()], flush=True)
    t = time.time(); out = s.run(None, {"image": img.numpy(), "mask": mask.numpy()})[0]; dt = time.time() - t
    print(S, "onnx", out.shape, float(out.min()), float(out.max()), f"{dt:.1f}s", "max diff vs torch", float(np.abs(out - ref.numpy()).max()), flush=True)
