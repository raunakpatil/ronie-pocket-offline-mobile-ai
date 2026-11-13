# Bake-off results (Q4_K_M, greedy; llama.cpp server, L4 GPU on Modal unless noted)

| model | mode | cards_v5 | cards_v6 | style_v6 | nsfw_v5 | nsfw_v5_sampled | nsfw_v4 | nsfw_v4_sampled | style_v4 | openness_v4 | style_v3 | openness_v3 | style | style_v2 | openness_v2 | openness | openness_open | tools | persona | grounded_qa | loops | summaries | triage_synthetic |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| gemma4e2b | nothink | 20% | 90% | 96% | 94% (cap 3%) | 91% | 83% | 87% | 100% | 100% (cap 8%) | 100% | 96% (cap 8%) | 48% (cap 4%) | 100% | 100% (cap 12%) | 72% | 100% (cap 4%) | 62% | 92% | 96% | 78% | 72% | 92% P 87% R 100% |
| lfm25 | nothink | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | 56% | 80% | 72% | 76% | 67% | 50% (cap 82%) P 50% R 100% |
| qwen08b | nothink | - | - | - | - | - | - | - | - | - | - | - | - | - | - | 88% (cap 12%) | 96% (cap 60%) | 36% | 80% (cap 3%) | 52% | 29% | 78% (cap 6%) | 50% P 50% R 100% |
| qwen08b | think | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | 58% (cap 4%) | - | - | 0% (cap 100%) | - | - |
| qwen2b | nothink | - | - | - | - | - | - | - | - | - | - | - | - | - | - | 72% (cap 4%) | 96% (cap 16%) | 66% | 87% | 96% | 60% | 67% (cap 6%) | 65% (cap 98%) P 100% R 30% |
| qwen2b | think | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | 68% | - | - | 0% (cap 100%) | - | - |
| qwen4b | nothink | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | 88% | 83% | 100% | 78% | 89% | 98% (cap 98%) P 100% R 95% |
| qwen4b | think | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | 90% | - | - | - | - | - |
