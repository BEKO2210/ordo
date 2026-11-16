#!/bin/bash
# eval4.sh TAG ADAPTER: dev-set evaluation like run_lora_eval.sh, but with gpu.lock only (VRAM-only job, ~1 GB RAM),
# started only with >= 10 000 MB RAM available and stopped if it falls below 8 000 MB (emulator runs have priority).
set -u
cd "$(dirname "$0")"
avail() { free -m | awk '/^Speicher|^Mem/{print $7}'; }
M=~/models/gguf/ordo-bench/Qwen3.5-4B-Q4_0.gguf
while [ "$(avail)" -lt 10000 ]; do sleep 30; done
flock -w 1800 ~/reel/gpu.lock timeout 1500 bash -c '
  ~/ordo-lora/llama.cpp/build/bin/llama-server -m '"$M"' --lora '"$2"' -ngl 99 -c 2048 -np 1 --host 127.0.0.1 --port 8097 > lora/server_'"$1"'.log 2>&1 &
  S=$!; trap "kill $S" EXIT
  for i in $(seq 120); do curl -sf http://127.0.0.1:8097/health >/dev/null && break; sleep 1; done
  python3 eval3.py run --url http://127.0.0.1:8097 --tag '"$1"' --short &
  E=$!
  while kill -0 $E 2>/dev/null; do [ "$(free -m | awk "/^Speicher|^Mem/{print \$7}")" -lt 8000 ] && { kill $E; echo RAM GUARD STOPPED; }; sleep 5; done
  wait $E
' > lora/eval_"$1".log 2>&1
tail -1 lora/eval_"$1".log; echo EVAL4 DONE
