#!/bin/bash
# lora_post.sh RUN: on pop-os after lora_train.py: PEFT adapter -> GGUF (f16) with the app's llama.cpp converter, hash,
# then raw plans with the Q4_0 base + adapter (run_lora_eval.sh). Prints size and SHA-256.
set -eu
cd ~/ordo-lora
.venv/bin/python llama.cpp/convert_lora_to_gguf.py --base base/Qwen3.5-4B --outtype f16 --outfile out/$1.gguf out/$1 2>&1 | tail -1
ls -l out/$1.gguf; sha256sum out/$1.gguf
cd lab && ./run_lora_eval.sh $1 ~/ordo-lora/out/$1.gguf > lora/eval_$1.log 2>&1; tail -1 lora/eval_$1.log
