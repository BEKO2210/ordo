# Run 4 wording loop: whenever >= 10 GB RAM are free for 60 s, one <= 8 min VRAM-only chunk (models rotate),
# guarded (stops below 8 GB). Ends when every job has wording.
cd ~/ordo-lora/lab
B=/usr/share/ollama/.ollama/models/blobs G=~/models/gguf
avail() { free -m | awk '/^Speicher|^Mem/{print $7}'; }
left() { python3 -c "
import json
done={json.loads(l)['job'] for l in open('lora2/words.jsonl')}
print(sum(1 for l in open('lora2/jobs.jsonl') if json.loads(l)['job'] not in done))"; }
i=0
while [ "$(left)" -gt 0 ]; do
  [ "$(avail)" -lt 10000 ] && { sleep 30; continue; }; sleep 60; [ "$(avail)" -lt 10000 ] && continue
  case $((i % 3)) in
    0) A=(granite4.2-8b 8 4 -- -m $B/sha256-16a9369d0805f80b7377d25d87f937a90c05dc04ad79173a52001e42c9aab311 -ngl 99 -c 12288 -np 4);;
    1) A=(qwen3.5-9b 8 4 -- -m $B/sha256-02d45dc1cf451ba2475ac33b301c2dd8f985abe4c182ce04a1f2f5bf0260278d -ngl 99 -c 16384 -np 4);;
    2) A=(ministral3-14b 8 2 -- -m $G/Ministral-3-14B-Instruct-2512-UD-IQ3_XXS.gguf -ngl 99 -c 6144 -np 2);;
  esac
  i=$((i + 1))
  echo "$(date +%T) chunk ${A[0]}, jobs left $(left)" >> lora2/chain.out
  ./gen_local.sh "${A[@]}" >> lora2/chain.out 2>&1 &
  P=$!
  while kill -0 $P 2>/dev/null; do
    [ "$(avail)" -lt 8000 ] && { pkill -f "lora_words.py run [0-9]"; echo "$(date +%T) RAM guard stopped chunk" >> lora2/chain.out; }
    sleep 5
  done
done
echo "$(date +%T) ALL JOBS WORDED" >> lora2/chain.out
