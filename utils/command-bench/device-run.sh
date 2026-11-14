#!/bin/bash
# device-run.sh <adb serial> <host port> <label> <threads>: llama-server on the phone, holdout bench via adb forward, RAM peak
set -u
A=~/Android/Sdk/platform-tools/adb; S=$1; P=$2; L=$3; T=$4
D=/data/local/tmp/ordo-llm
$A -s "$S" shell "cd $D && (LD_LIBRARY_PATH=. nohup ./llama-server -m Qwen3.5-2B-Q4_K_M.gguf -c 2048 -t $T --jinja -np 1 --host 127.0.0.1 --port 8085 > server.log 2>&1 &)"
$A -s "$S" forward tcp:$P tcp:8085
trap '$A -s "$S" shell killall llama-server; $A -s "$S" forward --remove tcp:$P' EXIT
for i in $(seq 120); do curl -sf http://127.0.0.1:$P/health >/dev/null && break; sleep 1; done
curl -sf http://127.0.0.1:$P/health >/dev/null || { echo "server not up"; $A -s "$S" shell tail -5 $D/server.log; exit 1; }
cd "$(dirname "$0")"
python3 bench.py "$L" --url http://127.0.0.1:$P --cases holdout.jsonl --out "$L.holdout.json"
$A -s "$S" shell 'grep -E "VmHWM|VmRSS" /proc/$(pidof llama-server)/status'
$A -s "$S" shell "grep -E 'prompt eval time|       eval time' $D/server.log | tail -4"
