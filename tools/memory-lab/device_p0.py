#!/usr/bin/env python3
"""Real device timing for S and M: the 20 original commands on the Galaxy Tab S9 Ultra.

    device_p0.py --url http://127.0.0.1:8097 --cond S --load-s 4.2   -> appends to device-p0.json

Talks to llama-server on the device (built from the app's llama.cpp commit with the app's flags, -c 2048, -t 4 =
CommandEngine "balanced", -b/-ub 512 like ordo_llm.cpp). cold = model load + system prompt + first command, as when
the sheet opens with no model loaded; warm = from the saved state after the system prompt, only memory + command
are new. The plans are kept, so device answers can be compared with the GPU run.
"""
import json
import pathlib
import sys
import time

import bench
import phase0 as p0

HERE = pathlib.Path(__file__).parent


def main():
    arg = lambda k, d=None: sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d
    url, cond, load_s = arg("--url"), arg("--cond"), float(arg("--load-s", 0))
    system, grammar = p0.prompts()[cond]
    pre = bench.prefix(system)
    files, history = p0.load()
    cases = [c for c in p0.all_cases() if c["kind"] == "original" and not c.get("restart")]
    users = []
    for c in cases:
        before, now_files, results, hist = p0.build_state(c, files, history)
        block, _ = p0.memory(cond, c, before, now_files, results, hist)
        users.append(f"{block}\nCommand: {c['text']}")

    def ask(user):
        bench.post(url, "/slots/0?action=restore", {"filename": "prefix.bin"})   # back to the state after the system prompt
        t = time.time()
        r = bench.post(url, "/completion", {"prompt": pre + bench.suffix(user), "grammar": grammar, "n_predict": p0.N_PREDICT,
                                            "temperature": 0, "cache_prompt": True})
        return time.time() - t, r

    # like CommandEngine.prepare + ordo_llm.cpp: the system prompt is evaluated once, its state saved, each command
    # starts from that state (the model is hybrid, so a plain prompt cache would re-read up to 512 tokens).
    t = time.time()
    r = bench.post(url, "/completion", {"prompt": pre, "n_predict": 0, "cache_prompt": True})
    prepare_s = time.time() - t
    bench.post(url, "/slots/0?action=save", {"filename": "prefix.bin"})
    rows = []
    for i, (c, user) in enumerate(zip(cases, users)):
        wall, r = ask(user)
        tm = r["timings"]
        rows.append({"cmd": c["cmd"], "warm_s": round(wall, 1), "cold_s": round(load_s + prepare_s + wall, 1) if i == 0 else None,
                     "cache_n": tm["cache_n"], "prompt_n": tm["prompt_n"], "prompt_tps": round(tm["prompt_per_second"], 1),
                     "predicted_n": tm["predicted_n"], "gen_tps": round(tm["predicted_per_second"], 1), "plan": r["content"]})
        print(rows[-1], flush=True)
    out = HERE / "device-p0.json"
    res = json.loads(out.read_text()) if out.exists() else {}
    res[cond] = {"system_tokens": bench.tokens(url, pre), "load_s": load_s, "prepare_s": round(prepare_s, 1), "rows": rows}
    out.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
