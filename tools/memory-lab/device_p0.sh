#!/bin/bash
# device_p0.sh <adb serial> S|M: llama-server on the Tab (no screen needed, nothing else touched), timing run, server off.
# Binary: llama.cpp at the app's submodule commit, NDK 30, Release, KleidiAI, no OpenMP (see GEDAECHTNIS-PHASE0.md).
set -u
A=~/Android/Sdk/platform-tools/adb; S=$1; C=$2; P=8097
D=/data/local/tmp/ordo-llm
cd "$(dirname "$0")"
temp() { $A -s "$S" shell "dumpsys battery | grep -m1 '  temperature'; dumpsys thermalservice | grep -m1 'Thermal Status'"; }
echo "before: $(temp | tr '\n' ' ')"
t0=$(date +%s.%N)
$A -s "$S" shell "mkdir -p $D/slots; cd $D && (nohup ./llama-server -m Qwen3.5-4B-Q4_0.gguf -c 2048 -t 4 -tb 4 -b 512 -ub 512 -np 1 \
  --slot-save-path $D/slots --host 127.0.0.1 --port 8085 > server.log 2>&1 &)"
$A -s "$S" forward tcp:$P tcp:8085
# Screen off = Doze: prompt speed drops to ~10 t/s (measured). The app only runs with the screen on, so the screen
# is kept awake (lock screen stays, nothing is unlocked or opened) with a wake key every 10 s.
( while true; do $A -s "$S" shell input keyevent KEYCODE_WAKEUP; sleep 10; done ) & W=$!
trap 'kill $W; $A -s "$S" shell killall llama-server; $A -s "$S" forward --remove tcp:$P' EXIT
for i in $(seq 240); do curl -sf http://127.0.0.1:$P/health >/dev/null && break; sleep 0.5; done
load=$(echo "$(date +%s.%N) - $t0" | bc); echo "load: $load s"
python3 device_p0.py --url http://127.0.0.1:$P --cond "$C" --load-s "$(LC_ALL=C printf %.1f "$load")"
$A -s "$S" shell 'grep -E "VmHWM|VmRSS" /proc/$(pidof llama-server)/status'
echo "after: $(temp | tr '\n' ' ')"
