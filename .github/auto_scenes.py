#!/usr/bin/env python3
"""Text points for the kinds of photo that Auto treats differently (night, sunset, food, portrait…),
worked out once with the same MobileCLIP2 text model the app uses, and shipped as a small JSON file."""
import json, sys, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer

SCENES = {
    "night": ["a photo taken at night", "a dark night scene with city lights", "a night sky"],
    "sunset": ["a sunset", "a sunrise over the horizon", "golden hour light"],
    "snow": ["a snowy landscape", "a photo of snow", "skiing in the snow"],
    "beach": ["a sunny beach", "the sea and sand on a bright day"],
    "food": ["a photo of food", "a plate of food on a table", "a dish at a restaurant"],
    "portrait": ["a portrait of a person", "a close-up photo of a face", "a selfie"],
    "landscape": ["a landscape with mountains", "nature scenery", "a wide view of fields and sky"],
    "indoor": ["an indoor photo of a room", "a photo taken indoors under warm lamp light"],
    "document": ["a document with text", "a screenshot of text", "a page of a book"],
    "backlit": ["a backlit photo of a person against a bright sky", "a dark silhouette against bright light"],
    "photo": ["a photo"],
}

tok = Tokenizer.from_file(sys.argv[2])
s = ort.InferenceSession(sys.argv[1], providers=["CPUExecutionProvider"])
def ids(t):
    e = tok.encode(t.lower()).ids
    if not e or e[0] != 49406: e = [49406] + e
    if e[-1] != 49407: e = e + [49407]
    e = e[:77]; return e + [0] * (77 - len(e))
out = {}
for k, prompts in SCENES.items():
    x = np.array([ids(p) for p in prompts], np.int64)
    v = s.run(None, {s.get_inputs()[0].name: x})[0]
    v = v / np.linalg.norm(v, axis=1, keepdims=True)
    m = v.mean(0); m = m / np.linalg.norm(m)
    out[k] = [round(float(f), 6) for f in m]
    print(k, "ok")
json.dump(out, open(sys.argv[3], "w"))
