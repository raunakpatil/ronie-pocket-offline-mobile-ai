// Phase 3.5 X-ray: dumps per-layer hidden states for a span of text, for training probes on the PC.
//
// Input  (JSONL): {"prefix": "<chat-formatted text up to the span>", "span": "the answer text"}
// Output (binary): for each line, float32[n_layer][n_embd] = mean of each layer's output (l_out)
//                  over the span's tokens. A sidecar <out>.json records n_layer, n_embd, count.
//
// Usage: probe_dump -m model.gguf -i in.jsonl -o out.bin [-t threads]
#include "llama.h"
#include "ggml-backend.h"
#include <nlohmann/json.hpp>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

struct Capture {
    int n_layer = 0, n_embd = 0;
    int span_from = 0, span_to = 0;     // token rows to average
    std::vector<float> sums;            // [n_layer][n_embd]
    std::vector<float> buf;
};

static bool cb(ggml_tensor *t, bool ask, void *ud) {
    auto *c = (Capture *)ud;
    const bool is_out = strncmp(t->name, "l_out-", 6) == 0;
    if (ask) return is_out;
    if (!is_out) return true;
    const int il = atoi(t->name + 6);
    if (il < 0 || il >= c->n_layer || t->ne[0] != c->n_embd) return true;
    const int rows = (int)t->ne[1];
    if (c->span_to > rows) return true;
    c->buf.resize((size_t)t->ne[0] * rows);
    ggml_backend_tensor_get(t, c->buf.data(), 0, ggml_nbytes(t));
    float *dst = c->sums.data() + (size_t)il * c->n_embd;
    for (int r = c->span_from; r < c->span_to; r++)
        for (int j = 0; j < c->n_embd; j++) dst[j] += c->buf[(size_t)r * c->n_embd + j];
    return true;
}

static std::vector<llama_token> tok(const llama_vocab *v, const std::string &s, bool bos) {
    int n = -llama_tokenize(v, s.c_str(), (int)s.size(), nullptr, 0, bos, true);
    std::vector<llama_token> out(n);
    llama_tokenize(v, s.c_str(), (int)s.size(), out.data(), n, bos, true);
    return out;
}

int main(int argc, char **argv) {
    std::string model, in, out;
    int threads = 8, ngl = 0;
    for (int i = 1; i + 1 < argc; i += 2) {
        std::string a = argv[i];
        if (a == "-m") model = argv[i + 1];
        else if (a == "-i") in = argv[i + 1];
        else if (a == "-o") out = argv[i + 1];
        else if (a == "-t") threads = atoi(argv[i + 1]);
        else if (a == "-ngl") ngl = atoi(argv[i + 1]);
    }
    if (model.empty() || in.empty() || out.empty()) {
        fprintf(stderr, "usage: probe_dump -m model.gguf -i in.jsonl -o out.bin [-t threads]\n");
        return 1;
    }
    ggml_backend_load_all();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = ngl;
    llama_model *m = llama_model_load_from_file(model.c_str(), mp);
    if (!m) return 1;
    const llama_vocab *vocab = llama_model_get_vocab(m);

    Capture cap;
    cap.n_layer = llama_model_n_layer(m);
    cap.n_embd = llama_model_n_embd(m);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 2048;
    cp.n_ubatch = 2048;
    cp.n_threads = cp.n_threads_batch = threads;
    cp.cb_eval = cb;
    cp.cb_eval_user_data = &cap;
    llama_context *ctx = llama_init_from_model(m, cp);
    if (!ctx) return 1;

    std::ifstream fin(in);
    std::ofstream fout(out, std::ios::binary);
    std::string line;
    int count = 0;
    while (std::getline(fin, line)) {
        if (line.empty()) continue;
        auto j = nlohmann::json::parse(line);
        const std::string prefix = j["prefix"], span = j["span"];
        auto t_pre = tok(vocab, prefix, true);
        auto t_all = tok(vocab, prefix + span, true);
        // Span = tokens after the shared prefix (robust to merges at the boundary).
        size_t k = 0;
        while (k < t_pre.size() && k < t_all.size() && t_pre[k] == t_all[k]) k++;
        if (k >= t_all.size()) { fprintf(stderr, "empty span on line %d\n", count); return 1; }
        cap.span_from = (int)k;
        cap.span_to = (int)t_all.size();
        cap.sums.assign((size_t)cap.n_layer * cap.n_embd, 0.0f);

        llama_memory_clear(llama_get_memory(ctx), true);
        llama_batch b = llama_batch_init((int)t_all.size(), 0, 1);
        for (size_t i = 0; i < t_all.size(); i++) {
            b.token[i] = t_all[i];
            b.pos[i] = (llama_pos)i;
            b.n_seq_id[i] = 1;
            b.seq_id[i][0] = 0;
            b.logits[i] = true;   // every row is an output, so no layer prunes rows away
        }
        b.n_tokens = (int)t_all.size();
        if (llama_decode(ctx, b) != 0) { fprintf(stderr, "decode failed on line %d\n", count); return 1; }
        llama_batch_free(b);

        const float n = (float)(cap.span_to - cap.span_from);
        for (auto &x : cap.sums) x /= n;
        fout.write((const char *)cap.sums.data(), sizeof(float) * cap.sums.size());
        if (++count % 25 == 0) fprintf(stderr, "%d done\n", count);
    }
    std::ofstream meta(out + ".json");
    meta << "{\"n_layer\": " << cap.n_layer << ", \"n_embd\": " << cap.n_embd << ", \"count\": " << count << "}\n";
    fprintf(stderr, "wrote %d rows to %s\n", count, out.c_str());
    llama_free(ctx);
    llama_model_free(m);
    return 0;
}
