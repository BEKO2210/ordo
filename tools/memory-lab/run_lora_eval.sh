#!/bin/bash
# run_lora_eval.sh TAG [ADAPTER.gguf] (with adapter: the short prompt SYSTEM_LORA, without: the long prompt SYSTEM): on pop-os, holds build.lock then gpu.lock; llama-server at the app's llama.cpp commit
# (3549c274, ~/ordo-lora/llama.cpp/build), the app's Q4_0 GGUF, greedy, schema-L grammar; raw plans -> lora/raw_TAG.json.
set -u
cd "$(dirname "$0")"
M=~/models/gguf/ordo-bench/Qwen3.5-4B-Q4_0.gguf
LORA=${2:+--lora $2}
exec flock -w 3600 ~/camhound-test/build.lock flock -w 1800 ~/reel/gpu.lock timeout 2400 bash -c '
  ~/ordo-lora/llama.cpp/build/bin/llama-server -m '"$M"' '"$LORA"' -ngl 99 -c 2048 -np 1 --host 127.0.0.1 --port 8097 > lora/server_'"$1"'.log 2>&1 &
  S=$!; trap "kill $S" EXIT
  for i in $(seq 120); do curl -sf http://127.0.0.1:8097/health >/dev/null && break; sleep 1; done
  python3 eval3.py run --url http://127.0.0.1:8097 --tag '"$1"' --val '"${2:+--short}"'
'
