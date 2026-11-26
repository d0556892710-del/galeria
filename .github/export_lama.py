"""Export big-lama (TorchScript) to ONNX at a fixed high resolution.

The stock exporter can't handle torch.fft.rfftn/irfftn, so they're written here with ONNX DFT
(complex numbers as a trailing dimension of 2), then checked against PyTorch.
"""
import time, sys, torch, numpy as np, onnxruntime as ort
from torch.onnx import register_custom_op_symbolic

torch.set_grad_enabled(False)
OPSET = 17
INV_NORMALIZED = True  # does ONNX DFT(inverse=1) include 1/n? decided by the check below

def C(g, v): return g.op("Constant", value_t=torch.tensor(v, dtype=torch.int64))

def last(g, z, i):  # real (0) or imaginary (1) part of [..., 2]
    return g.op("Squeeze", g.op("Slice", z, C(g, [i]), C(g, [i + 1]), C(g, [-1])), C(g, [-1]))

def scale(g, t):  # sqrt(H*W) of a [B,C,H,W] tensor, as float
    sh = g.op("Shape", t)
    h = g.op("Gather", sh, C(g, 2), axis_i=0); w = g.op("Gather", sh, C(g, 3), axis_i=0)
    return g.op("Sqrt", g.op("Cast", g.op("Mul", h, w), to_i=1))

def rfftn(g, x, s, dim, norm):
    xu = g.op("Unsqueeze", x, C(g, [-1]))
    y = g.op("DFT", xu, axis_i=3, onesided_i=1, inverse_i=0)
    y = g.op("DFT", y, axis_i=2, onesided_i=0, inverse_i=0)
    return g.op("Div", y, scale(g, x))

def irfftn(g, z, s, dim, norm):
    y = g.op("DFT", z, axis_i=2, onesided_i=0, inverse_i=1)
    mid = g.op("Slice", y, C(g, [-2]), C(g, [0]), C(g, [3]), C(g, [-1]))
    conj = g.op("Concat", g.op("Unsqueeze", last(g, mid, 0), C(g, [-1])), g.op("Unsqueeze", g.op("Neg", last(g, mid, 1)), C(g, [-1])), axis_i=-1)
    full = g.op("Concat", y, conj, axis_i=3)
    out = g.op("DFT", full, axis_i=3, onesided_i=0, inverse_i=1)
    r = last(g, out, 0)
    return g.op("Mul", r, scale(g, r)) if INV_NORMALIZED else g.op("Div", r, scale(g, r))

def real(g, z): return last(g, z, 0)
def imag(g, z): return last(g, z, 1)
def complex_(g, re, im):
    return g.op("Concat", g.op("Unsqueeze", re, C(g, [-1])), g.op("Unsqueeze", im, C(g, [-1])), axis_i=-1)

for name, fn in (("aten::fft_rfftn", rfftn), ("aten::fft_irfftn", irfftn), ("aten::real", real), ("aten::imag", imag), ("aten::complex", complex_)):
    register_custom_op_symbolic(name, fn, OPSET)

m = torch.jit.load("big-lama.pt", map_location="cpu").eval()
print("loaded", flush=True)
sizes = [int(a) for a in sys.argv[1:]] or [768, 1024]
for S in sizes:
    g = torch.Generator().manual_seed(0)
    img = torch.rand(1, 3, S, S, generator=g)
    mask = torch.zeros(1, 1, S, S); mask[:, :, S // 4: S // 2, S // 3: 2 * S // 3] = 1
    ref = m(img, mask).numpy()
    name = f"lama_{S}.onnx"
    for inv in (True, False):
        INV_NORMALIZED = inv
        try:
            torch.onnx.export(m, (img, mask), name, input_names=["image", "mask"], output_names=["output"], opset_version=OPSET)
        except Exception as e:
            print(S, "export failed:", repr(e)[:1500], flush=True); break
        sess = ort.InferenceSession(name, providers=["CPUExecutionProvider"])
        t = time.time(); out = sess.run(None, {"image": img.numpy(), "mask": mask.numpy()})[0]; dt = time.time() - t
        d = float(np.abs(out - ref).max())
        print(S, f"inverse-normalized={inv}", "out", out.shape, f"{dt:.1f}s", "max diff vs torch", d, flush=True)
        if d < 2e-2:
            print(S, "OK", flush=True); break
