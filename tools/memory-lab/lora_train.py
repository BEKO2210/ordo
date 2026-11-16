#!/usr/bin/env python3
"""QLoRA on Qwen/Qwen3.5-4B (the safetensors of the app's GGUF) for schema L. Runs on pop-os in ~/ordo-lora/.venv.

    lora_train.py RUN [--epochs 2] [--lr 2e-4] [--r 16] [--minutes 38]
Data: lab/lora/train.jsonl + val.jsonl (prompt = chat template exactly as the app sends it, completion = plan JSON + <|im_end|>).
Loss only on the completion. Targets: MLP of all 32 layers + q/k/v/o of the 8 full-attention layers (the linear-attention
projections are reordered by llama.cpp's converter, so a LoRA on them would not survive the conversion). MTP head untouched.
Writes out/RUN/ (PEFT adapter) and out/RUN/log.jsonl (train loss every 10 steps, val loss every 100 and at the end).
"""
import json
import math
import os
import random
import sys
import time

import torch
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig, get_cosine_schedule_with_warmup

BASE = "/home/belkis/ordo-lora/base/Qwen3.5-4B"
LAB = "/home/belkis/ordo-lora/lab/" + os.environ.get("LORA_DIR", "lora")
arg = lambda k, d: type(d)(sys.argv[sys.argv.index(k) + 1]) if k in sys.argv else d
RUN, EPOCHS, LR, RANK, MINUTES = sys.argv[1], arg("--epochs", 2), arg("--lr", 2e-4), arg("--r", 16), arg("--minutes", 38.0)
MICRO, ACCUM = 4, 4
OUTD = f"/home/belkis/ordo-lora/out/{RUN}"
torch.manual_seed(0)
random.seed(0)

tok = AutoTokenizer.from_pretrained(BASE)


def load(name):
    rows = []
    for l in open(f"{LAB}/{name}.jsonl", encoding="utf-8"):
        d = json.loads(l)
        p = tok(d["prompt"], add_special_tokens=False).input_ids
        c = tok(d["completion"], add_special_tokens=False).input_ids
        rows.append((p + c, [-100] * len(p) + c))
    return rows


train, val = load("train"), load("val")
if "--probe" in sys.argv:   # speed/memory probe: a few steps only
    train, val = train[:160], val[:16]
print("train", len(train), "val", len(val), "max len", max(len(x) for x, _ in train), flush=True)


def batches(rows, shuffle):
    idx = list(range(len(rows)))
    if shuffle:
        random.shuffle(idx)
    for i in range(0, len(idx), MICRO):
        part = [rows[j] for j in idx[i:i + MICRO]]
        n = max(len(x) for x, _ in part)
        ids = torch.full((len(part), n), tok.pad_token_id or 0)
        lab = torch.full((len(part), n), -100)
        att = torch.zeros((len(part), n), dtype=torch.long)
        for k, (x, y) in enumerate(part):   # left padding: every completion ends at the last position
            ids[k, n - len(x):], lab[k, n - len(y):], att[k, n - len(x):] = torch.tensor(x), torch.tensor(y), 1
        yield ids.cuda(), lab.cuda(), att.cuda()


def loss_of(ids, lab, att):
    """Logits only for the tail that holds the completions (vocab 248k: full logits would not fit in 8 GB)."""
    keep = int((lab != -100).sum(1).max().item()) + 1
    logits = model(input_ids=ids, attention_mask=att, logits_to_keep=keep).logits.float()
    tgt = lab[:, -keep + 1:]
    ce = torch.nn.functional.cross_entropy(logits[:, :-1].reshape(-1, logits.size(-1)), tgt.reshape(-1), ignore_index=-100,
                                           reduction="sum")
    return ce, (tgt != -100).sum()


model = AutoModelForCausalLM.from_pretrained(BASE, quantization_config=BitsAndBytesConfig(
    load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype=torch.bfloat16, bnb_4bit_use_double_quant=True),
    dtype=torch.bfloat16, device_map={"": 0})
model = prepare_model_for_kbit_training(model, use_gradient_checkpointing=True)
cfg = LoraConfig(r=RANK, lora_alpha=2 * RANK, lora_dropout=0.05, task_type="CAUSAL_LM",
                 target_modules=r"model\.(language_model\.)?layers\.\d+\.(mlp\.(gate|up|down)_proj|self_attn\.(q|k|v|o)_proj)")
model = get_peft_model(model, cfg)
model.print_trainable_parameters()
opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=LR, weight_decay=0.0)
steps = math.ceil(len(train) / (MICRO * ACCUM)) * EPOCHS
sched = get_cosine_schedule_with_warmup(opt, max(10, steps // 20), steps)
log = open(f"{OUTD}.log.jsonl", "w")


def val_loss():
    model.eval()
    tot = n = 0
    with torch.no_grad():
        for ids, lab, att in batches(val, False):
            ce, k = loss_of(ids, lab, att)
            tot, n = tot + ce.item(), n + k.item()
    model.train()
    return tot / max(n, 1)


t0 = time.time()
step = micro = 0
v0 = val_loss()
print("val loss before", round(v0, 4), flush=True)
log.write(json.dumps({"step": 0, "val": v0}) + "\n")
model.train()
stop = False
run_loss = []
for ep in range(EPOCHS):
    for ids, lab, att in batches(train, True):
        ce, k = loss_of(ids, lab, att)
        loss = ce / k / ACCUM
        loss.backward()
        run_loss.append(loss.item() * ACCUM)
        micro += 1
        if micro % ACCUM:
            continue
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        opt.zero_grad(set_to_none=True)
        step += 1
        if step % 10 == 0:
            rec = {"step": step, "epoch": ep, "train": sum(run_loss) / len(run_loss), "lr": sched.get_last_lr()[0],
                   "min": round((time.time() - t0) / 60, 1)}
            run_loss = []
            if step % 100 == 0:
                rec["val"] = val_loss()
            print(rec, flush=True)
            log.write(json.dumps(rec) + "\n")
            log.flush()
        if (time.time() - t0) / 60 > MINUTES:
            print("time cap reached at step", step, flush=True)
            stop = True
            break
    if stop:
        break
vf = val_loss()
rec = {"step": step, "val": vf, "min": round((time.time() - t0) / 60, 1), "final": True}
print(rec, flush=True)
log.write(json.dumps(rec) + "\n")
model.save_pretrained(OUTD)
print("saved", OUTD, flush=True)
