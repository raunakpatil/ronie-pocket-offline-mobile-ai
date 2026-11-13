// Ronie Pocket engine: a thin JNI layer over llama.cpp.
//
// - Backends (7 CPU variants + Vulkan) are loaded at runtime from the app's native lib dir.
// - Inference threads are pinned to the big CPU cores (threads ggml spawns inherit the affinity).
// - Tokens are streamed to Kotlin as raw UTF-8 byte chunks (NewStringUTF breaks on emoji).
// - The prompt KV/state is reused across turns when the new prompt extends the old one.

#include <jni.h>
#include <android/log.h>
#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "gguf.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "RonieEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}

// ---------------------------------------------------------------- big-core pinning

// Returns the CPUs whose max frequency is above the lowest cluster's (i.e. not the little cores).
std::vector<int> big_cores() {
    std::vector<std::pair<int, long>> cpus;
    long n = sysconf(_SC_NPROCESSORS_CONF);
    for (int i = 0; i < n; ++i) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE *f = fopen(path, "r");
        long freq = 0;
        if (f) { if (fscanf(f, "%ld", &freq) != 1) freq = 0; fclose(f); }
        cpus.emplace_back(i, freq);
    }
    long min_f = LONG_MAX;
    for (auto &c : cpus) if (c.second > 0) min_f = std::min(min_f, c.second);
    std::vector<int> big;
    for (auto &c : cpus) if (c.second > min_f) big.push_back(c.first);
    if (big.empty()) for (auto &c : cpus) big.push_back(c.first);  // uniform cores: use all
    return big;
}

void pin_current_thread(const std::vector<int> &cores) {
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c : cores) CPU_SET(c, &set);
    if (sched_setaffinity(0, sizeof(set), &set) != 0) LOGE("sched_setaffinity failed");
}

// ---------------------------------------------------------------- engine state

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_sampler *sampler = nullptr;
    std::vector<llama_token> cached;   // tokens currently represented in the context memory
    std::vector<int> cores;
    int n_ctx = 0;
    bool on_gpu = false;
    // Steering (Phase 3.5): one direction per layer, unscaled; layer 1 at [0] (llama_set_adapter_cvec layout).
    std::vector<float> steer_dir;
    std::vector<float> steer_scaled;
    int steer_n_embd = 0;
    // X-ray probe (Phase 3.5): linear score w·h + b on one layer's output for the newest token.
    bool probe_on = false;
    std::string probe_tensor;          // e.g. "l_out-17"
    std::vector<float> probe_w, probe_row;
    float probe_b = 0.0f;
    float probe_last = NAN;            // score of the most recently decoded token (NaN = none)

    // Multimodal / vision context (Phase 9)
    mtmd_context *mtmd = nullptr;
    std::string mmproj_path;

    ~Engine() {
        if (mtmd) mtmd_free(mtmd);
        if (sampler) llama_sampler_free(sampler);
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Length of the longest prefix of `s` that ends on a complete UTF-8 character.
size_t utf8_complete_prefix(const std::string &s) {
    size_t i = s.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char)s[i - 1];
        if ((c & 0xC0) != 0x80) {  // lead byte or ASCII
            size_t need = (c < 0x80) ? 1 : ((c >> 5) == 0x6) ? 2 : ((c >> 4) == 0xE) ? 3 : ((c >> 3) == 0x1E) ? 4 : 1;
            return (back + 1 >= need) ? s.size() : i - 1;
        }
        --i; ++back;
    }
    return s.size();
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), nullptr, 0, /*add_special*/ false, /*parse_special*/ true);
    std::vector<llama_token> toks(n);
    llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), toks.data(), n, false, true);
    return toks;
}

std::string piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    // special = true: tool calls are made of special tokens (<|tool_call>, <|"|>); the app strips any
    // control tokens before showing or speaking text (ToolSyntax.visible).
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, /*special*/ true);
    return n > 0 ? std::string(buf, n) : std::string();
}

// Graph callback: when X-ray is on, copies the probe layer's last row (the newest token) to the CPU
// and scores it. Asking for only this one tensor keeps the cost to one small copy per token.
bool probe_cb(ggml_tensor *t, bool ask, void *ud) {
    auto *e = (Engine *)ud;
    if (!e->probe_on || e->probe_tensor != t->name) return ask ? false : true;
    if (ask) return true;
    const int64_t n_embd = t->ne[0], rows = t->ne[1];
    if (n_embd != (int64_t)e->probe_w.size() || rows < 1 || t->type != GGML_TYPE_F32) return true;
    e->probe_row.resize(n_embd);
    ggml_backend_tensor_get(t, e->probe_row.data(), (size_t)(rows - 1) * t->nb[1], sizeof(float) * n_embd);
    float s = e->probe_b;
    for (int64_t j = 0; j < n_embd; j++) s += e->probe_w[j] * e->probe_row[j];
    e->probe_last = s;
    return true;
}

bool decode_chunked(Engine *e, std::vector<llama_token> &toks, size_t from) {
    const size_t batch = 512;
    for (size_t i = from; i < toks.size(); i += batch) {
        size_t n = std::min(batch, toks.size() - i);
        if (llama_decode(e->ctx, llama_batch_get_one(toks.data() + i, (int32_t)n)) != 0) return false;
    }
    return true;
}

// After a failed decode the context memory no longer matches e->cached. Forget both, so the next
// prompt is decoded from scratch instead of reusing a prefix that isn't really there.
jstring fail_and_reset(JNIEnv *env, Engine *e, const char *msg) {
    llama_memory_clear(llama_get_memory(e->ctx), true);
    e->cached.clear();
    LOGE("generate failed: %s (context reset)", msg);
    std::string j = std::string("{\"error\":\"") + msg + "\"}";
    return env->NewStringUTF(j.c_str());
}

uint32_t fnv1a_32(const char *s) {
    uint32_t h = 2166136261u;
    if (!s) return h;
    while (*s) {
        h ^= (uint8_t)*s++;
        h *= 16777619u;
    }
    return h;
}

struct SamplingResult {
    double first_token_ms = -1;
    int generated = 0;
    double gen_ms = 0;
    bool stopped_by_user = false;
};

SamplingResult run_sampling_loop(JNIEnv *env, Engine *e, jobject callback, jmethodID onBytes,
                                 int maxTokens, Clock::time_point t0) {
    llama_sampler_reset(e->sampler);
    e->probe_last = NAN;   // the prompt's last token isn't part of the reply
    auto t1 = Clock::now();
    SamplingResult res;
    std::string pending;
    for (; res.generated < maxTokens; ++res.generated) {
        llama_token t = llama_sampler_sample(e->sampler, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, t)) break;
        if (res.first_token_ms < 0) res.first_token_ms = ms_since(t0);
        pending += piece(e->vocab, t);
        size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            jbyteArray arr = env->NewByteArray((jsize)ok);
            env->SetByteArrayRegion(arr, 0, (jsize)ok, (const jbyte *)pending.data());
            // X-ray score of the token decoded just before this chunk (NaN when X-ray is off).
            jboolean cont = env->CallBooleanMethod(callback, onBytes, arr, (jfloat)e->probe_last);
            env->DeleteLocalRef(arr);
            pending.erase(0, ok);
            if (!cont) { res.stopped_by_user = true; ++res.generated; e->cached.push_back(t); break; }
        }
        e->cached.push_back(t);
        llama_token one = t;
        if (llama_decode(e->ctx, llama_batch_get_one(&one, 1)) != 0) break;
    }
    res.gen_ms = ms_since(t1);
    return res;
}

}  // namespace

extern "C" {

// Where the Vulkan backend keeps its pipeline cache (our ggml-vulkan patch reads GGML_VK_PIPELINE_CACHE when it
// creates the device). Must be called before nativeInit / the first model load.
JNIEXPORT void JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeSetGpuCache(JNIEnv *env, jclass, jstring path) {
    setenv("GGML_VK_PIPELINE_CACHE", jstr(env, path).c_str(), 1);
}

// Writes the GPU pipeline cache to disk. Returns false if no backend offers it.
JNIEXPORT jboolean JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeSaveGpuCache(JNIEnv *, jclass) {
    bool any = false;
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        auto *reg = ggml_backend_reg_get(i);
        auto fn = (void (*)()) ggml_backend_reg_get_proc_address(reg, "ggml_backend_vk_save_pipeline_cache");
        if (fn) { fn(); any = true; }
    }
    return any;
}

// Loads backend plugins from the app's native library dir. Returns a short device summary.
JNIEXPORT jstring JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeInit(JNIEnv *env, jclass, jstring libDir) {
    static bool done = false;
    if (!done) {
        llama_backend_init();
        ggml_backend_load_all_from_path(jstr(env, libDir).c_str());
        done = true;
    }
    std::string s;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto *d = ggml_backend_dev_get(i);
        if (!s.empty()) s += "; ";
        s += ggml_backend_dev_name(d);
        s += " (";
        s += ggml_backend_dev_description(d);
        s += ")";
    }
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeLoad(JNIEnv *env, jclass, jstring path,
                                                                jint nGpuLayers, jint nCtx, jint nThreads) {
    auto e = std::make_unique<Engine>();
    e->cores = big_cores();
    pin_current_thread(e->cores);

    auto mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;
    e->model = llama_model_load_from_file(jstr(env, path).c_str(), mp);
    if (!e->model) { LOGE("model load failed"); return 0; }

    auto cp = llama_context_default_params();
    cp.n_ctx = (uint32_t)nCtx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    int threads = nThreads > 0 ? nThreads : (int)std::min<size_t>(4, e->cores.size());
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = false;
    // Explicit (not "auto"): the persona state is computed on the PC (tools-pc/persona_state) and shipped in
    // the app, and the KV layout depends on this setting, so both sides must match.
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cp.cb_eval = probe_cb;              // inert unless X-ray is switched on
    cp.cb_eval_user_data = e.get();
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) { LOGE("context init failed"); return 0; }

    e->vocab = llama_model_get_vocab(e->model);
    e->n_ctx = nCtx;
    e->on_gpu = nGpuLayers != 0;
    LOGI("loaded: gpu_layers=%d ctx=%d threads=%d big_cores=%zu", nGpuLayers, nCtx, threads, e->cores.size());
    return (jlong)e.release();
}

JNIEXPORT void JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeSetSampler(JNIEnv *, jclass, jlong h, jfloat temp, jint topK,
                                                                      jfloat topP, jfloat minP, jfloat presence, jint seed) {
    auto *e = (Engine *)h;
    if (e->sampler) llama_sampler_free(e->sampler);
    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = false;
    e->sampler = llama_sampler_chain_init(sp);
    if (presence != 0.0f)
        llama_sampler_chain_add(e->sampler, llama_sampler_init_penalties(
            llama_vocab_n_tokens(e->vocab), /*last_n*/ 256, /*repeat*/ 1.0f, /*freq*/ 0.0f, presence));
    if (temp <= 0.0f) {
        llama_sampler_chain_add(e->sampler, llama_sampler_init_greedy());
        return;
    }
    if (topK > 0) llama_sampler_chain_add(e->sampler, llama_sampler_init_top_k(topK));
    if (topP < 1.0f) llama_sampler_chain_add(e->sampler, llama_sampler_init_top_p(topP, 1));
    if (minP > 0.0f) llama_sampler_chain_add(e->sampler, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(e->sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(e->sampler, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t)seed));
}

// Generates a reply for the fully formatted prompt. Calls callback.onBytes(byte[]) for each
// complete UTF-8 chunk; if it returns false, generation stops. Returns timing stats as JSON.
JNIEXPORT jstring JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeGenerate(JNIEnv *env, jclass, jlong h, jstring prompt,
                                                                    jint maxTokens, jobject callback) {
    auto *e = (Engine *)h;
    pin_current_thread(e->cores);
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onBytes = env->GetMethodID(cbClass, "onBytes", "([BF)Z");

    auto t0 = Clock::now();
    std::vector<llama_token> toks = tokenize(e->vocab, jstr(env, prompt));
    if ((int)toks.size() + maxTokens > e->n_ctx) {
        // Keep it simple for the MVP: refuse instead of silently truncating history.
        return env->NewStringUTF("{\"error\":\"prompt too long\"}");
    }

    // Reuse the shared prefix with what's already in memory (persona prompt, earlier turns).
    size_t common = 0;
    while (common < toks.size() && common < e->cached.size() && toks[common] == e->cached[common]) ++common;
    if (common == toks.size()) common = toks.size() - 1;  // always re-decode at least the last token for logits
    llama_memory_t mem = llama_get_memory(e->ctx);
    bool reused = common > 0 && llama_memory_seq_rm(mem, 0, (llama_pos)common, -1);
    if (!reused) {  // recurrent/SWA models may not support partial removal: start over
        llama_memory_clear(mem, true);
        common = 0;
    }
    if (!decode_chunked(e, toks, common)) return fail_and_reset(env, e, "prompt decode failed");
    double prompt_ms = ms_since(t0);
    size_t prompt_new = toks.size() - common;
    e->cached = toks;

    auto sres = run_sampling_loop(env, e, callback, onBytes, maxTokens, t0);

    char json[512];
    snprintf(json, sizeof(json),
             "{\"prompt_tokens\":%zu,\"prompt_new_tokens\":%zu,\"prompt_ms\":%.1f,\"first_token_ms\":%.1f,"
             "\"gen_tokens\":%d,\"gen_ms\":%.1f,\"tok_per_s\":%.2f,\"reused_prefix\":%s,\"stopped\":%s,\"gpu\":%s}",
             toks.size(), prompt_new, prompt_ms, sres.first_token_ms, sres.generated, sres.gen_ms,
             sres.gen_ms > 0 ? sres.generated * 1000.0 / sres.gen_ms : 0.0, reused ? "true" : "false",
             sres.stopped_by_user ? "true" : "false", e->on_gpu ? "true" : "false");
    return env->NewStringUTF(json);
}

// Loads mmproj multimodal projector. Lazy: called before the first multimodal generation.
JNIEXPORT jboolean JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeLoadVision(
    JNIEnv *env, jclass, jlong h, jstring mmprojPath, jboolean useGpu, jint maxImageTokens) {
    auto *e = (Engine *)h;
    std::string path = jstr(env, mmprojPath);
    if (e->mtmd && e->mmproj_path == path) {
        return JNI_TRUE;
    }
    if (e->mtmd) {
        mtmd_free(e->mtmd);
        e->mtmd = nullptr;
        e->mmproj_path.clear();
    }
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = useGpu;
    params.n_threads = 4;
    params.warmup = false;
    if (maxImageTokens > 0) {
        params.image_max_tokens = maxImageTokens;
    }
    e->mtmd = mtmd_init_from_file(path.c_str(), e->model, params);
    if (!e->mtmd) {
        LOGE("mtmd_init_from_file failed for %s", path.c_str());
        return JNI_FALSE;
    }
    e->mmproj_path = path;
    LOGI("loaded vision mmproj: %s (max_tokens=%d, gpu=%d)", path.c_str(), maxImageTokens, (int)useGpu);
    return JNI_TRUE;
}

// Frees multimodal vision context.
JNIEXPORT void JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeFreeVision(JNIEnv *, jclass, jlong h) {
    auto *e = (Engine *)h;
    if (e->mtmd) {
        mtmd_free(e->mtmd);
        e->mtmd = nullptr;
        e->mmproj_path.clear();
        LOGI("freed vision mmproj");
    }
}

// Returns the media marker tag expected in prompts (e.g. "<__media__>").
JNIEXPORT jstring JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeMediaMarker(JNIEnv *env, jclass) {
    return env->NewStringUTF(mtmd_default_marker());
}

// Multimodal generation with images. Reuses cached prefix via negative sentinel tokens for images.
JNIEXPORT jstring JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeGenerateMM(
    JNIEnv *env, jclass, jlong h, jstring prompt, jobjectArray imagePaths, jint maxTokens, jobject callback) {
    auto *e = (Engine *)h;
    if (!e->mtmd) {
        return env->NewStringUTF("{\"error\":\"vision model not loaded\"}");
    }
    pin_current_thread(e->cores);
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onBytes = env->GetMethodID(cbClass, "onBytes", "([BF)Z");

    auto t0 = Clock::now();

    // 1. Collect image paths and load bitmaps
    std::vector<std::string> paths;
    jsize n_imgs = env->GetArrayLength(imagePaths);
    for (jsize i = 0; i < n_imgs; ++i) {
        auto jstr_elem = (jstring)env->GetObjectArrayElement(imagePaths, i);
        paths.push_back(jstr(env, jstr_elem));
        env->DeleteLocalRef(jstr_elem);
    }

    std::vector<mtmd_bitmap *> bitmaps;
    std::vector<mtmd_helper_video *> video_ctxs;
    auto opt = mtmd_helper_init_opt_default();
    bool load_ok = true;
    for (const auto &p : paths) {
        auto res = mtmd_helper_bitmap_init_from_file(e->mtmd, p.c_str(), false, opt);
        if (!res.bitmap) {
            LOGE("failed to load bitmap from %s", p.c_str());
            load_ok = false;
            break;
        }
        bitmaps.push_back(res.bitmap);
        if (res.video_ctx) video_ctxs.push_back(res.video_ctx);
    }

    if (!load_ok) {
        for (auto *b : bitmaps) mtmd_bitmap_free(b);
        for (auto *v : video_ctxs) mtmd_helper_video_free(v);
        return env->NewStringUTF("{\"error\":\"failed to load image file\"}");
    }

    // 2. Tokenize prompt + bitmaps with mtmd_tokenize
    std::string prompt_str = jstr(env, prompt);
    mtmd_input_text inp_text = {
        prompt_str.c_str(),
        prompt_str.size(),
        /*add_special=*/ false,
        /*parse_special=*/ true
    };
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    int32_t tok_res = mtmd_tokenize(e->mtmd, chunks, &inp_text, bitmaps.data(), bitmaps.size());

    // Bitmaps are not needed after tokenization
    for (auto *b : bitmaps) mtmd_bitmap_free(b);
    bitmaps.clear();
    for (auto *v : video_ctxs) mtmd_helper_video_free(v);
    video_ctxs.clear();

    if (tok_res != 0) {
        LOGE("mtmd_tokenize failed: %d", tok_res);
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("{\"error\":\"mtmd_tokenize failed\"}");
    }

    // 3. Flatten chunks into position key sequence for prefix caching
    std::vector<llama_token> pos_keys;
    std::vector<size_t> chunk_positions;
    size_t n_chunks = mtmd_input_chunks_size(chunks);
    chunk_positions.reserve(n_chunks);

    for (size_t i = 0; i < n_chunks; ++i) {
        auto chunk = mtmd_input_chunks_get(chunks, i);
        chunk_positions.push_back(pos_keys.size());
        auto type = mtmd_input_chunk_get_type(chunk);
        if (type == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            size_t n_tok = 0;
            const llama_token *toks = mtmd_input_chunk_get_tokens_text(chunk, &n_tok);
            for (size_t j = 0; j < n_tok; ++j) {
                pos_keys.push_back(toks[j]);
            }
        } else {
            const char *id = mtmd_input_chunk_get_id(chunk);
            uint32_t h = fnv1a_32(id);
            int32_t sentinel = -(int32_t)(h & 0x3fffffff) - 1;
            llama_pos n_pos = mtmd_input_chunk_get_n_pos(chunk);
            for (llama_pos p = 0; p < n_pos; ++p) {
                pos_keys.push_back((llama_token)sentinel);
            }
        }
    }

    if ((int)pos_keys.size() + maxTokens > e->n_ctx) {
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("{\"error\":\"prompt too long\"}");
    }

    // 4. Find common prefix with e->cached
    size_t common = 0;
    while (common < pos_keys.size() && common < e->cached.size() && pos_keys[common] == e->cached[common]) {
        ++common;
    }
    if (common == pos_keys.size()) {
        common = pos_keys.size() - 1;
    }

    // If common falls inside any image chunk, back it up to that image's start
    for (size_t c = 0; c < n_chunks; ++c) {
        auto chunk = mtmd_input_chunks_get(chunks, c);
        if (mtmd_input_chunk_get_type(chunk) != MTMD_INPUT_CHUNK_TYPE_TEXT) {
            size_t chunk_start = chunk_positions[c];
            size_t chunk_len = (size_t)mtmd_input_chunk_get_n_pos(chunk);
            size_t chunk_end = chunk_start + chunk_len;
            if (common > chunk_start && common < chunk_end) {
                common = chunk_start;
            }
        }
    }

    llama_memory_t mem = llama_get_memory(e->ctx);
    bool reused = common > 0 && llama_memory_seq_rm(mem, 0, (llama_pos)common, -1);
    if (!reused) {
        llama_memory_clear(mem, true);
        common = 0;
    }

    // 5. Evaluate only chunks after common
    llama_pos n_past = (llama_pos)common;
    double image_ms = 0;
    int image_tokens = 0;

    for (size_t c = 0; c < n_chunks; ++c) {
        auto chunk = mtmd_input_chunks_get(chunks, c);
        auto chunk_type = mtmd_input_chunk_get_type(chunk);
        size_t chunk_start = chunk_positions[c];
        size_t chunk_len = (size_t)mtmd_input_chunk_get_n_pos(chunk);
        size_t chunk_end = chunk_start + chunk_len;

        if (common >= chunk_end) {
            continue;
        }

        if (chunk_type == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            size_t n_tok = 0;
            const llama_token *toks = mtmd_input_chunk_get_tokens_text(chunk, &n_tok);
            size_t offset = (common > chunk_start) ? (common - chunk_start) : 0;
            size_t to_decode = n_tok - offset;
            if (to_decode > 0) {
                std::vector<llama_token> text_slice(toks + offset, toks + n_tok);
                if (!decode_chunked(e, text_slice, 0)) {
                    mtmd_input_chunks_free(chunks);
                    return fail_and_reset(env, e, "text decode failed");
                }
                n_past += (llama_pos)to_decode;
            }
        } else {
            auto t_img = Clock::now();
            int enc_res = mtmd_encode_chunk(e->mtmd, chunk);
            if (enc_res != 0) {
                LOGE("mtmd_encode_chunk failed: %d", enc_res);
                mtmd_input_chunks_free(chunks);
                return fail_and_reset(env, e, "image encode failed");
            }
            float *embd = mtmd_get_output_embd(e->mtmd);
            llama_pos new_past = n_past;
            int dec_res = mtmd_helper_decode_image_chunk(
                e->mtmd, e->ctx, chunk, embd, n_past, 0, 512, &new_past, nullptr, nullptr);
            if (dec_res != 0) {
                LOGE("mtmd_helper_decode_image_chunk failed: %d", dec_res);
                mtmd_input_chunks_free(chunks);
                return fail_and_reset(env, e, "image decode failed");
            }
            image_ms += ms_since(t_img);
            image_tokens += (int)mtmd_input_chunk_get_n_tokens(chunk);
            n_past = new_past;
        }
    }

    mtmd_input_chunks_free(chunks);

    double prompt_ms = ms_since(t0);
    size_t prompt_new = pos_keys.size() - common;
    e->cached = pos_keys;

    // 6. Sampling loop
    auto sres = run_sampling_loop(env, e, callback, onBytes, maxTokens, t0);

    char json[512];
    snprintf(json, sizeof(json),
             "{\"prompt_tokens\":%zu,\"prompt_new_tokens\":%zu,\"prompt_ms\":%.1f,\"first_token_ms\":%.1f,"
             "\"gen_tokens\":%d,\"gen_ms\":%.1f,\"tok_per_s\":%.2f,\"reused_prefix\":%s,\"stopped\":%s,\"gpu\":%s,"
             "\"image_ms\":%.1f,\"image_tokens\":%d}",
             pos_keys.size(), prompt_new, prompt_ms, sres.first_token_ms, sres.generated, sres.gen_ms,
             sres.gen_ms > 0 ? sres.generated * 1000.0 / sres.gen_ms : 0.0, reused ? "true" : "false",
             sres.stopped_by_user ? "true" : "false", e->on_gpu ? "true" : "false",
             image_ms, image_tokens);
    return env->NewStringUTF(json);
}

// Decodes `text` into the context without generating (e.g. the persona prompt at startup), so the
// first real message only processes the user's words. Also warms up GPU pipelines. Returns ms taken.
JNIEXPORT jdouble JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativePrefill(JNIEnv *env, jclass, jlong h, jstring text) {
    auto *e = (Engine *)h;
    pin_current_thread(e->cores);
    auto t0 = Clock::now();
    std::vector<llama_token> toks = tokenize(e->vocab, jstr(env, text));
    llama_memory_clear(llama_get_memory(e->ctx), true);
    e->cached.clear();
    if (toks.empty() || !decode_chunked(e, toks, 0)) return -1.0;
    e->cached = toks;
    return ms_since(t0);
}

// Saves the current sequence state (e.g. the prefilled persona prompt) to a file. Returns bytes written (0 = failed).
JNIEXPORT jlong JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeSaveState(JNIEnv *env, jclass, jlong h, jstring path) {
    auto *e = (Engine *)h;
    if (e->cached.empty()) return 0;
    size_t n = llama_state_seq_save_file(e->ctx, jstr(env, path).c_str(), 0, e->cached.data(), e->cached.size());
    return (jlong)n;
}

// Restores a state saved by nativeSaveState, but only if it was built from exactly the tokens of
// `expectedText` (so a changed persona or model can never reuse a stale cache). Returns ms taken, or -1.
JNIEXPORT jdouble JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeLoadState(JNIEnv *env, jclass, jlong h, jstring path,
                                                                     jstring expectedText) {
    auto *e = (Engine *)h;
    auto t0 = Clock::now();
    std::vector<llama_token> expected = tokenize(e->vocab, jstr(env, expectedText));
    std::vector<llama_token> got(expected.size() + 16);
    size_t n_got = 0;
    llama_memory_clear(llama_get_memory(e->ctx), true);
    e->cached.clear();
    size_t read = llama_state_seq_load_file(e->ctx, jstr(env, path).c_str(), 0, got.data(), got.size(), &n_got);
    got.resize(n_got);
    if (read == 0 || got != expected) {
        llama_memory_clear(llama_get_memory(e->ctx), true);
        LOGI("state cache miss (read=%zu, tokens %zu vs %zu)", read, n_got, expected.size());
        return -1.0;
    }
    e->cached = got;
    return ms_since(t0);
}

JNIEXPORT void JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeReset(JNIEnv *, jclass, jlong h) {
    auto *e = (Engine *)h;
    llama_memory_clear(llama_get_memory(e->ctx), true);
    e->cached.clear();
}

// Loads a control-vector GGUF ("direction.<layer>" F32 tensors). Returns the number of layers, or -1.
JNIEXPORT jint JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeLoadSteering(JNIEnv *env, jclass, jlong h, jstring jpath) {
    auto *e = (Engine *)h;
    const std::string path = jstr(env, jpath);
    ggml_context *gctx = nullptr;
    gguf_init_params p = {/*no_alloc*/ false, /*ctx*/ &gctx};
    gguf_context *g = gguf_init_from_file(path.c_str(), p);
    if (!g) { LOGE("steering: can't read %s", path.c_str()); return -1; }
    const int n_embd = llama_model_n_embd(e->model);
    const int n_layer = llama_model_n_layer(e->model);
    std::vector<float> dir((size_t)n_embd * n_layer, 0.0f);
    int loaded = 0;
    for (int i = 0; i < gguf_get_n_tensors(g); i++) {
        const char *name = gguf_get_tensor_name(g, i);
        if (strncmp(name, "direction.", 10) != 0) continue;
        const int il = atoi(name + 10);
        ggml_tensor *t = ggml_get_tensor(gctx, name);
        if (il < 1 || il > n_layer || !t || t->type != GGML_TYPE_F32 || ggml_nelements(t) != n_embd) {
            LOGE("steering: bad tensor %s", name);
            loaded = -1;
            break;
        }
        memcpy(dir.data() + (size_t)n_embd * (il - 1), t->data, sizeof(float) * n_embd);
        loaded++;
    }
    gguf_free(g);
    ggml_free(gctx);
    if (loaded <= 0) return -1;
    e->steer_dir = std::move(dir);
    e->steer_n_embd = n_embd;
    LOGI("steering: loaded %d layers from %s", loaded, path.c_str());
    return loaded;
}

// strength 0 turns steering off. Applies to tokens processed from now on; cached tokens keep their state.
JNIEXPORT jboolean JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeSetSteering(JNIEnv *, jclass, jlong h, jfloat strength,
                                                                       jint ilStart, jint ilEnd) {
    auto *e = (Engine *)h;
    if (strength == 0.0f || e->steer_dir.empty()) {
        return llama_set_adapter_cvec(e->ctx, nullptr, 0, 0, 0, 0) == 0;
    }
    e->steer_scaled.resize(e->steer_dir.size());
    for (size_t i = 0; i < e->steer_dir.size(); i++) e->steer_scaled[i] = e->steer_dir[i] * strength;
    return llama_set_adapter_cvec(e->ctx, e->steer_scaled.data(), e->steer_scaled.size(), e->steer_n_embd,
                                  ilStart, ilEnd) == 0;
}

// Installs (weights != null) or switches off (weights == null) the X-ray probe.
JNIEXPORT jboolean JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeSetProbe(JNIEnv *env, jclass, jlong h, jint layer,
                                                                    jfloatArray weights, jfloat bias) {
    auto *e = (Engine *)h;
    if (!weights) { e->probe_on = false; e->probe_last = NAN; return JNI_TRUE; }
    const jsize n = env->GetArrayLength(weights);
    if (n != llama_model_n_embd(e->model) || layer < 0 || layer >= llama_model_n_layer(e->model)) return JNI_FALSE;
    e->probe_w.resize(n);
    env->GetFloatArrayRegion(weights, 0, n, e->probe_w.data());
    e->probe_b = bias;
    e->probe_tensor = "l_out-" + std::to_string(layer);
    e->probe_on = true;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_raunakpatil_roniepocket_engine_LlamaEngine_nativeFree(JNIEnv *, jclass, jlong h) {
    delete (Engine *)h;
}

}  // extern "C"
