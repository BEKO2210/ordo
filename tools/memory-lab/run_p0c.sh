#!/bin/bash
# run_p0c.sh C: on pop-os under the GPU lock, the app's GGUF, context 2048, Phase-0c raw plans (results-0c.json).
set -u
cd "$(dirname "$0")"
M=~/models/gguf/ordo-bench/Qwen3.5-4B-Q4_0.gguf
for _ in $(seq 60); do [ "$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)" -ge 3000 ] && break; sleep 10; done
exec flock -w 900 ~/reel/gpu.lock timeout 1500 bash -c '
  env LD_LIBRARY_PATH=/usr/local/lib/ollama/cuda_v12:/usr/local/lib/ollama \
    GGML_BACKEND_PATH=/usr/local/lib/ollama/cuda_v12/libggml-cuda.so \
    /usr/local/lib/ollama/llama-server -m '"$M"' -ngl 99 -c 2048 -np 1 --host 127.0.0.1 --port 8096 > server.log 2>&1 &
  S=$!; trap "kill $S" EXIT
  for i in $(seq 90); do curl -sf http://127.0.0.1:8096/health >/dev/null && break; sleep 1; done
  python3 phase0c.py run --url http://127.0.0.1:8096 --only '"${1:-C}"'
'
