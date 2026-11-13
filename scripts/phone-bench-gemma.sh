#!/system/bin/sh
# Phase 2: Gemma 4 E2B vs Qwen3.5-2B speed on the Pixel (big cores, 4 threads).
cd /data/local/tmp/ronie || exit 1
t() { echo "  [cpu7 max $(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq) kHz, battery $(cat /sys/class/power_supply/battery/temp)]"; }

run() {  # $1 = build dir, $2 = model name, $3 = extra args
  echo "== $1 $2 $3"; t
  taskset f0 ./$1/llama-bench -m models/$2.gguf $3 -p 256 -n 64 -t 4 -r 2 -o md 2>&1 | grep -e pp256 -e tg64 -e rror
}

run kleidi Qwen3.5-2B-Q4_K_M ""
run kleidi gemma-4-E2B-it-Q4_K_M ""
run vulkan gemma-4-E2B-it-Q4_K_M "-ngl 99"
t
echo "== memory when loaded (Gemma 4 E2B, ctx 4096)"
taskset f0 ./kleidi/llama-completion -m models/gemma-4-E2B-it-Q4_K_M.gguf -p hi -n 4 -t 4 -c 4096 -no-cnv 2>&1 \
  | grep -i -e "buffer size" | head -8
echo "== memory when loaded (Qwen3.5-2B, ctx 4096)"
taskset f0 ./kleidi/llama-completion -m models/Qwen3.5-2B-Q4_K_M.gguf -p hi -n 4 -t 4 -c 4096 -no-cnv 2>&1 \
  | grep -i -e "buffer size" | head -8
echo "== GEMMA-BENCH DONE"
