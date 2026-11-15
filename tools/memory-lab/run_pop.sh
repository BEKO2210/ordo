#!/bin/bash
# run_pop.sh [A,B,C]: on pop-os, under the GPU lock: llama-server with the app's exact GGUF, bench, server off.
set -u
cd "$(dirname "$0")"
M=~/models/gguf/ordo-bench/Qwen3.5-4B-Q4_0.gguf   # sha256 298fcb5f… = CommandModel.SHA256
exec flock -w 900 ~/reel/gpu.lock timeout 1200 bash -c '
  env LD_LIBRARY_PATH=/usr/local/lib/ollama/cuda_v12:/usr/local/lib/ollama \
    GGML_BACKEND_PATH=/usr/local/lib/ollama/cuda_v12/libggml-cuda.so \
    /usr/local/lib/ollama/llama-server -m '"$M"' -ngl 99 -c 4096 -np 1 --host 127.0.0.1 --port 8096 > server.log 2>&1 &
  S=$!; trap "kill $S" EXIT
  for i in $(seq 90); do curl -sf http://127.0.0.1:8096/health >/dev/null && break; sleep 1; done
  for c in ${CASES:-commands.jsonl holdout.jsonl}; do python3 bench.py run --url http://127.0.0.1:8096 --only '"${1:-A,B,C}"' --cases $c; done
'
