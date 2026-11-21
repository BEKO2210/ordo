# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
"""Runs Ordo's generated GLSL ES passes (MpvHookTest writes them to app/build/detailplus-glsl) on desktop GL the way
DetailPlusProgram does on the phone, and prints PSNR (RGB, dB) of the doubled frame against the original and against
libplacebo's run of the same mpv shader. The pass directives come from the mpv file itself.
usage: harness.py <frag dir> <mpv shader> <small.png> <ref.png> <libplacebo.png or -> [noflip]"""
import sys, re, os
import numpy as np, moderngl
from PIL import Image

frag_dir, shader, small, ref, placebo = sys.argv[1:6]
noflip = "noflip" in sys.argv
name = os.path.basename(shader)
ctx = moderngl.create_standalone_context(require=430, backend="egl")
VERT = {"300": "#version 300 es\nin vec2 aPosition;\nvoid main() { gl_Position = vec4(aPosition, 0.0, 1.0); }",
        "100": "#version 100\nattribute vec2 aPosition;\nvoid main() { gl_Position = vec4(aPosition, 0.0, 1.0); }"}
quad = ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], "f4").tobytes())
K = np.array([0.2126, 0.7152, 0.0722], "f4")

def upright(arr):  # row 0 = top of the image -> GL texture rows (row 0 = bottom), as Media3 hands frames over
    t = ctx.texture((arr.shape[1], arr.shape[0]), 4, np.ascontiguousarray(arr[::-1]).astype("f4").tobytes(), dtype="f4")
    t.filter = (moderngl.LINEAR, moderngl.LINEAR); t.repeat_x = t.repeat_y = False
    return t

def draw(src, size, textures, uniforms):
    prog = ctx.program(vertex_shader=VERT["300" if src.startswith("#version 300") else "100"], fragment_shader=src)
    out = ctx.texture(size, 4, dtype="f2"); out.filter = (moderngl.LINEAR, moderngl.LINEAR)
    out.repeat_x = out.repeat_y = False
    fbo = ctx.framebuffer([out]); fbo.use(); ctx.viewport = (0, 0) + tuple(size)
    for unit, (n, t) in enumerate(textures.items()):
        if n in prog: t.use(unit); prog[n].value = unit
    for k, v in uniforms.items():
        if k in prog: prog[k].value = v
    ctx.vertex_array(prog, [(quad, "2f", "aPosition")]).render(moderngl.TRIANGLE_STRIP)
    return out

passes, cur = [], None
for line in open(shader):
    if line.startswith("//!"):
        k, _, v = line[3:].strip().partition(" ")
        if cur is None or cur["body"]:
            cur = {"binds": [], "body": False}; passes.append(cur)
        if k == "BIND": cur["binds"].append(v)
        elif k in ("HOOK", "SAVE", "WIDTH", "HEIGHT"): cur[k] = v
    elif cur is not None and line.strip(): cur["body"] = True

img = np.asarray(Image.open(small).convert("RGB"), "f4") / 255.0
h, w = img.shape[:2]
rgba = np.concatenate([img, np.ones((h, w, 1), "f4")], 2)
inp = upright(rgba)
luma = passes[0]["HOOK"] == "LUMA"
plane = passes[0]["HOOK"]
texs = {plane: upright(np.dstack([img @ K] * 3 + [np.ones((h, w), "f4")])) if luma else inp}
sizes = {plane: (w, h)}

def ev(expr):
    st = []
    for t in expr.split():
        if t in ("+", "-", "*", "/"):
            b, a = st.pop(), st.pop(); st.append({"+": a + b, "-": a - b, "*": a * b, "/": a / b}[t])
        elif re.fullmatch(r"[\d.]+", t): st.append(float(t))
        else:
            n, c = t.split("."); st.append(sizes[n][0 if c[0] == "w" else 1])
    return int(st[0])

for i, p in enumerate(passes):
    src = open(f"{frag_dir}/{name}_{i}.frag").read()
    if noflip:
        src = src.replace("uOutSize.y - gl_FragCoord.y", "gl_FragCoord.y").replace("1.0 - (p).y", "(p).y")
    hooked = sizes[p["HOOK"]]
    size = (ev(p["WIDTH"]) if "WIDTH" in p else hooked[0], ev(p["HEIGHT"]) if "HEIGHT" in p else hooked[1])
    used = list(dict.fromkeys(p["binds"] + [p["HOOK"]]))
    uni = {"uOutSize": size}
    for n in used: uni[n + "_size"] = sizes[n]
    out = draw(src, size, {n + "_raw": texs[n] for n in used}, uni)
    target = p.get("SAVE", p["HOOK"])
    texs[target], sizes[target] = out, size

PRESENT = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "present.frag")).read()
W, H = sizes[plane]
res = draw(PRESENT, (W, H), {"uInput": inp, "uResult": texs[plane]}, {"uOutSize": (W, H), "uLuma": 1.0 if luma else 0.0})
out = np.frombuffer(res.read(), "f2").astype("f4").reshape(H, W, 4)[::-1, :, :3]
out8 = np.clip(np.round(out * 255), 0, 255)

def psnr(a, b):
    return 10 * np.log10(255.0 ** 2 / np.mean((a - b) ** 2))

refi = np.asarray(Image.open(ref).convert("RGB"), "f4")
line = f"{name} {'noflip' if noflip else 'flip'}: vs original {psnr(out8, refi):.2f} dB"
if placebo != "-":
    line += f", vs libplacebo {psnr(out8, np.asarray(Image.open(placebo).convert('RGB'), 'f4')):.2f} dB"
print(line)
