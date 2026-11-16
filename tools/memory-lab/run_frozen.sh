#!/bin/bash
# run_frozen.sh CASES_MODULE TAG: the frozen state on a new case list (on pop-os, in ~/ordo-lora/lab).
#   CASES_MODULE: python module in this folder with cases() in blind2_cases format (+ optional EXTRA files)
#   -> lora/raw_TAG.json (raw plans: App-GGUF Q4_0 + frozen adapter, SYSTEM_LORA, rules3.grammar(), greedy)
#   -> lora/results_ext.md (model alone and model + net a-d, scoring like blind2_run)
set -eu
cd "$(dirname "$0")"
ADAPTER=${ADAPTER:-$HOME/ordo-lora/out/FROZEN.gguf}
M=~/models/gguf/ordo-bench/Qwen3.5-4B-Q4_0.gguf
export EVAL_CASES=$1
flock -w 3600 ~/camhound-test/build.lock flock -w 1800 ~/reel/gpu.lock timeout 2400 bash -c '
  ~/ordo-lora/llama.cpp/build/bin/llama-server -m '"$M"' --lora '"$ADAPTER"' -ngl 99 -c 2048 -np 1 --host 127.0.0.1 --port 8097 > lora/server_'"$2"'.log 2>&1 &
  S=$!; trap "kill $S" EXIT
  for i in $(seq 120); do curl -sf http://127.0.0.1:8097/health >/dev/null && break; sleep 1; done
  python3 eval3.py run --url http://127.0.0.1:8097 --tag '"$2"' --short
'
python3 eval3.py report "$2"
