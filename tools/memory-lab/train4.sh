#!/bin/bash
# train4.sh RUN: adapter no. 2 (clean data in $LORA_DIR, default lora2/; run5: LORA_DIR=lora3). Waits until nobody queues on build.lock, then holds build.lock and
# gpu.lock only for the training (<= 25 min) and converts to GGUF. Same recipe as run2: QLoRA r16, 1 epoch, LR 2e-4.
set -u
cd "$(dirname "$0")"
export LORA_DIR=${LORA_DIR:-lora2}
free_build() { while [ "$(pgrep -f '^flock .*build.lock' | wc -l)" -gt 0 ]; do sleep 20; done; }
free_build
flock -w 600 ~/camhound-test/build.lock flock -w 600 ~/reel/gpu.lock timeout 1500 \
  ~/ordo-lora/.venv/bin/python lora_train.py "$1" --epochs 1 --minutes 22 > ~/ordo-lora/out/"$1".out 2>&1 || { echo TRAIN FAILED; exit 1; }
cd ~/ordo-lora && .venv/bin/python llama.cpp/convert_lora_to_gguf.py --base base/Qwen3.5-4B --outtype f16 --outfile out/"$1".gguf out/"$1" 2>&1 | tail -1
ls -l out/"$1".gguf && sha256sum out/"$1".gguf
echo TRAIN4 DONE   # evaluation: eval4.sh (gpu.lock only, RAM-gated)
