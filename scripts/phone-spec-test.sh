#!/system/bin/sh
# Runs on the phone: 2B baseline vs 2B + 0.8B speculative drafting (KleidiAI CPU build, big cores).
cd /data/local/tmp/ronie || exit 1
P='Write a short, friendly email to my landlord saying the kitchen tap is leaking and asking when someone can come to fix it this week.'
temp() { echo "  [cpu7 max $(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq) kHz, battery $(cat /sys/class/power_supply/battery/temp)]"; }

echo "== baseline 2B (kleidi)"; temp
taskset f0 ./kleidi/llama-completion -m models/Qwen3.5-2B-Q4_K_M.gguf -p "$P" -n 128 --temp 0 -t 4 -c 4096 -no-cnv 2>&1 | grep -e 'eval time'

for n in 4 8; do
  echo "== spec 2B + 0.8B draft, draft-max $n"; temp
  taskset f0 ./kleidi/llama-speculative-simple -m models/Qwen3.5-2B-Q4_K_M.gguf -md models/Qwen3.5-0.8B-Q4_K_M.gguf \
    --spec-type draft-simple --spec-draft-n-max $n -p "$P" -n 128 --temp 0 -t 4 -c 4096 2>&1 \
    | grep -i -e 'decoded' -e 'accept' -e 'n_draft' -e 'n_predict' -e 'speed'
done

echo "== spec 2B + ngram (no draft model)"; temp
taskset f0 ./kleidi/llama-speculative-simple -m models/Qwen3.5-2B-Q4_K_M.gguf --spec-type ngram-simple \
  -p "$P" -n 128 --temp 0 -t 4 -c 4096 2>&1 | grep -i -e 'decoded' -e 'accept' -e 'speed'
echo "== SPEC-TEST DONE"; temp
