#!/bin/bash
# gen_local.sh NAME MINUTES THREADS -- LLAMA_ARGS...: run 4 wording with a local model on pop-os (no cloud).
# JOBS=n caps the jobs per thread. Holds gpu.lock only (not build.lock), starts Ollama's llama-server on :8180 with the given GGUF, runs
# lora_words.py with THREADS parallel requests for at most MINUTES, then frees the GPU. Output: $LORA_DIR/words.jsonl.
set -u
cd "$(dirname "$0")"
NAME=$1 MIN=$2 TH=$3; shift 4
export LORA_DIR=${LORA_DIR:-lora2}
BACK=""; for i in $(seq "$TH"); do BACK="$BACK local:8180:$NAME"; done
exec flock -w 1800 ~/reel/gpu.lock bash -c '
  env LD_LIBRARY_PATH=/usr/local/lib/ollama/cuda_v12:/usr/local/lib/ollama GGML_BACKEND_PATH=/usr/local/lib/ollama/cuda_v12/libggml-cuda.so \
    /usr/local/lib/ollama/llama-server "$@" -fa on -ctk q8_0 -ctv q8_0 --jinja --host 127.0.0.1 --port 8180 > $LORA_DIR/server_'"$NAME"'.log 2>&1 &
  S=$!; trap "kill $S" EXIT
  for i in $(seq 300); do curl -sf http://127.0.0.1:8180/health >/dev/null && break; sleep 1; done
  timeout '"$((MIN * 60))"' python3 lora_words.py run '"${JOBS:-999}"' '"$BACK"'
' _ "$@"
