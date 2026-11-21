#!/usr/bin/env python3
"""Makes the AI pictures of art/prompts.json as art/<group>/<name>.jpg (existing files stay; delete one to redo it).

Usage: art.py   Needs the logged-in `higgsfield` CLI; model nano_banana_pro at 1k costs 2 credits per picture
(21.11.2026), so a full set of 16 costs 32. The pictures are committed: a rerun gives other pictures.
"""
import io, json, os, subprocess, urllib.request
from PIL import Image

HERE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "art")
P = json.load(open(os.path.join(HERE, "prompts.json")))
RATIO = {"covers": "1:1", "photos": "4:3"}
for group, ratio in RATIO.items():
    os.makedirs(os.path.join(HERE, group), exist_ok=True)
    for name, prompt in P[group].items():
        dst = os.path.join(HERE, group, name + ".jpg")
        if os.path.exists(dst):
            continue
        out = subprocess.run(["higgsfield", "generate", "create", "nano_banana_pro", "--prompt",
                              prompt + ", " + P["style"], "--aspect-ratio", ratio, "--resolution", "1k",
                              "--wait", "--json"], capture_output=True, text=True, check=True).stdout
        url = json.loads(out[out.index("["):])[0]["result_url"]
        Image.open(io.BytesIO(urllib.request.urlopen(url, timeout=120).read())).convert("RGB").save(dst, quality=88)
        print("ok", dst, flush=True)
