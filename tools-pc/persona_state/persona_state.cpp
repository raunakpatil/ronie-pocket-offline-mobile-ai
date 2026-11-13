// Computes Ronie's persona state on the PC so the app can ship it (fast first launch).
//
//   persona_state <model.gguf> <prefix.txt> <out.bin> [threads]
//
// Reads the exact fixed prompt text the app uses (written by the PersonaPrefixExport unit test), feeds it
// through the model with the same context settings as the phone engine (core/engine ronie_engine.cpp:
// n_ctx 8192, batch 512, flash attention off), and saves sequence 0 with llama_state_seq_save_file, which is
// exactly what the app's loadState reads. The app also re-checks the tokens before using it.
#include "llama.h"
#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: persona_state model.gguf prefix.txt out.bin [threads]\n"); return 2; }
    const int threads = argc > 4 ? atoi(argv[4]) : 8;
    std::ifstream f(argv[2], std::ios::binary);
    std::stringstream ss; ss << f.rdbuf();
    const std::string text = ss.str();

    llama_backend_init();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(argv[1], mp);
    if (!model) { fprintf(stderr, "model load failed\n"); return 1; }

    auto cp = llama_context_default_params();
    cp.n_ctx = 8192;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;   // must match the phone engine
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { fprintf(stderr, "context init failed\n"); return 1; }

    // Tokenize exactly like the app: no extra BOS (the text has its own), special tokens parsed.
    const llama_vocab *vocab = llama_model_get_vocab(model);
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), nullptr, 0, false, true);
    std::vector<llama_token> toks(n);
    llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), toks.data(), n, false, true);

    auto t0 = std::chrono::steady_clock::now();
    for (size_t i = 0; i < toks.size(); i += 512) {
        int32_t k = (int32_t)std::min<size_t>(512, toks.size() - i);
        if (llama_decode(ctx, llama_batch_get_one(toks.data() + i, k)) != 0) { fprintf(stderr, "decode failed\n"); return 1; }
    }
    double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    size_t bytes = llama_state_seq_save_file(ctx, argv[3], 0, toks.data(), toks.size());
    if (bytes == 0) { fprintf(stderr, "save failed\n"); return 1; }
    printf("persona_state: %zu tokens in %.1f s -> %s (%.1f MB)\n", toks.size(), s, argv[3], bytes / 1e6);
    llama_free(ctx);
    llama_model_free(model);
    return 0;
}
