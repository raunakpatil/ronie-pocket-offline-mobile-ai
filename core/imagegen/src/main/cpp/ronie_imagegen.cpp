#include <jni.h>
#include <android/log.h>
#include <malloc.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <cstring>
#include "stable-diffusion.h"

#define TAG "RonieImageGen"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void sd_log_callback_bridge(enum sd_log_level_t level, const char* text, void* /* data */) {
    switch (level) {
        case SD_LOG_DEBUG:
            break;   // very chatty; not needed on the phone
        case SD_LOG_INFO:
            LOGI("[SD-INFO] %s", text);
            break;
        case SD_LOG_WARN:
            LOGW("[SD-WARN] %s", text);
            break;
        case SD_LOG_ERROR:
            LOGE("[SD-ERROR] %s", text);
            break;
        default:
            LOGI("[SD] %s", text);
            break;
    }
}

struct RonieSdContext {
    sd_ctx_t* ctx;
    bool hasTaesd;
};

struct StepUserData {
    JNIEnv* env;
    jobject callback;
    jmethodID onStepMethod;
    int totalSteps;
};

static void preview_callback_bridge(int step, int frame_count, sd_image_t* frames, bool /* is_noisy */, void* data) {
    if (!data || frame_count <= 0 || !frames || !frames[0].data) return;
    StepUserData* udata = static_cast<StepUserData*>(data);
    if (!udata->callback || !udata->onStepMethod) return;

    const sd_image_t& img = frames[0];
    int w = static_cast<int>(img.width);
    int h = static_cast<int>(img.height);
    int totalPixels = w * h;

    jintArray pixelArray = udata->env->NewIntArray(totalPixels);
    if (!pixelArray) return;

    jint* raw = udata->env->GetIntArrayElements(pixelArray, nullptr);
    if (img.channel == 3) {
        for (int i = 0; i < totalPixels; ++i) {
            uint8_t r = img.data[i * 3 + 0];
            uint8_t g = img.data[i * 3 + 1];
            uint8_t b = img.data[i * 3 + 2];
            raw[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
    } else if (img.channel == 4) {
        for (int i = 0; i < totalPixels; ++i) {
            uint8_t r = img.data[i * 4 + 0];
            uint8_t g = img.data[i * 4 + 1];
            uint8_t b = img.data[i * 4 + 2];
            uint8_t a = img.data[i * 4 + 3];
            raw[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }
    udata->env->ReleaseIntArrayElements(pixelArray, raw, 0);

    udata->env->CallVoidMethod(udata->callback, udata->onStepMethod, step, udata->totalSteps, pixelArray, w, h);
    udata->env->DeleteLocalRef(pixelArray);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_raunakpatil_roniepocket_imagegen_SdNative_initContext(
    JNIEnv* env,
    jobject /* thiz */,
    jstring modelPath,
    jstring taesdPath,
    jint nThreads,
    jboolean useVulkan
) {
    if (!modelPath) {
        LOGE("initContext: modelPath is null");
        return 0;
    }
    const char* pathStr = env->GetStringUTFChars(modelPath, nullptr);
    if (!pathStr) return 0;

    const char* taeStr = taesdPath ? env->GetStringUTFChars(taesdPath, nullptr) : nullptr;
    bool hasTae = (taeStr && strlen(taeStr) > 0);

    sd_set_log_callback(sd_log_callback_bridge, nullptr);

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = pathStr;
    if (hasTae) {
        params.taesd_path = taeStr;
        LOGI("Configured TAESD path: %s", taeStr);
    }
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.diffusion_flash_attn = true;
    params.flash_attn = true;

    sd_ctx_t* ctx = nullptr;
    if (useVulkan) {
        params.backend = "vulkan";
        LOGI("Attempting new_sd_ctx with Vulkan backend for model: %s (taesd: %s)",
             pathStr, hasTae ? taeStr : "none");
        ctx = new_sd_ctx(&params);
        if (!ctx) {
            LOGW("Vulkan init failed; falling back to CPU backend");
        }
    }

    if (!ctx) {
        params.backend = "cpu";
        LOGI("Attempting new_sd_ctx with CPU backend for model: %s (taesd: %s)",
             pathStr, hasTae ? taeStr : "none");
        ctx = new_sd_ctx(&params);
    }

    if (taeStr) env->ReleaseStringUTFChars(taesdPath, taeStr);
    env->ReleaseStringUTFChars(modelPath, pathStr);

    if (!ctx) {
        LOGE("Failed to create sd_ctx_t");
        return 0;
    }

    RonieSdContext* wrapper = new RonieSdContext();
    wrapper->ctx = ctx;
    wrapper->hasTaesd = hasTae;

    LOGI("sd_ctx_t created successfully: %p (hasTaesd=%d)", ctx, (int)hasTae);
    return reinterpret_cast<jlong>(wrapper);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_raunakpatil_roniepocket_imagegen_SdNative_generate(
    JNIEnv* env,
    jobject /* thiz */,
    jlong ctxHandle,
    jstring prompt,
    jstring negativePrompt,
    jint width,
    jint height,
    jint steps,
    jfloat cfgScale,
    jint sampleMethod,
    jint scheduler,
    jlong seed,
    jobject callback
) {
    RonieSdContext* wrapper = reinterpret_cast<RonieSdContext*>(ctxHandle);
    if (!wrapper || !wrapper->ctx) {
        LOGE("generate: null context");
        return nullptr;
    }
    sd_ctx_t* ctx = wrapper->ctx;

    const char* promptStr = prompt ? env->GetStringUTFChars(prompt, nullptr) : "";
    const char* negPromptStr = negativePrompt ? env->GetStringUTFChars(negativePrompt, nullptr) : "";

    sd_img_gen_params_t gen_params;
    sd_img_gen_params_init(&gen_params);
    gen_params.prompt = promptStr;
    gen_params.negative_prompt = negPromptStr;
    gen_params.clip_skip = -1;
    gen_params.width = width;
    gen_params.height = height;
    gen_params.sample_params.sample_steps = steps;
    gen_params.sample_params.guidance.txt_cfg = cfgScale;

    if (sampleMethod >= 0 && sampleMethod < SAMPLE_METHOD_COUNT) {
        gen_params.sample_params.sample_method = static_cast<sample_method_t>(sampleMethod);
    } else {
        gen_params.sample_params.sample_method = EULER_A_SAMPLE_METHOD;
    }

    if (scheduler >= 0 && scheduler < SCHEDULER_COUNT) {
        gen_params.sample_params.scheduler = static_cast<scheduler_t>(scheduler);
    } else {
        gen_params.sample_params.scheduler = DISCRETE_SCHEDULER;
    }

    gen_params.seed = seed;
    gen_params.batch_count = 1;

    LOGI("Generating image: size=%dx%d, steps=%d, cfg=%.2f, method=%d, scheduler=%d, seed=%lld, callback=%p",
         width, height, steps, cfgScale, (int)gen_params.sample_params.sample_method,
         (int)gen_params.sample_params.scheduler, (long long)seed, callback);

    StepUserData udata{env, callback, nullptr, steps};
    if (callback) {
        jclass cbClass = env->GetObjectClass(callback);
        if (cbClass) {
            udata.onStepMethod = env->GetMethodID(cbClass, "onStep", "(II[III)V");
            if (udata.onStepMethod) {
                enum preview_t pmode = wrapper->hasTaesd ? PREVIEW_TAE : PREVIEW_PROJ;
                LOGI("Registering SD preview callback: mode=%d (hasTaesd=%d)", (int)pmode, (int)wrapper->hasTaesd);
                sd_set_preview_callback(preview_callback_bridge, pmode, 1, true, false, &udata);
            } else {
                LOGW("Could not find onStep(II[III)V on callback");
            }
        }
    }

    sd_image_generation_result_t* result = nullptr;
    bool ok = generate_image(ctx, &gen_params, &result);

    if (callback && udata.onStepMethod) {
        sd_set_preview_callback(nullptr, PREVIEW_NONE, 0, false, false, nullptr);
    }

    if (prompt) env->ReleaseStringUTFChars(prompt, promptStr);
    if (negativePrompt) env->ReleaseStringUTFChars(negativePrompt, negPromptStr);

    if (!ok || !result || result->count == 0 || !result->items[0].image.data) {
        LOGE("generate_image failed or returned no images (ok=%d, result=%p)", ok, result);
        if (result) free_sd_image_generation_result(result);
        return nullptr;
    }

    const sd_image_t& img = result->items[0].image;
    uint32_t totalPixels = img.width * img.height;
    std::vector<jint> argbPixels(totalPixels);

    if (img.channel == 3) {
        for (uint32_t i = 0; i < totalPixels; ++i) {
            uint8_t r = img.data[i * 3 + 0];
            uint8_t g = img.data[i * 3 + 1];
            uint8_t b = img.data[i * 3 + 2];
            argbPixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
    } else if (img.channel == 4) {
        for (uint32_t i = 0; i < totalPixels; ++i) {
            uint8_t r = img.data[i * 4 + 0];
            uint8_t g = img.data[i * 4 + 1];
            uint8_t b = img.data[i * 4 + 2];
            uint8_t a = img.data[i * 4 + 3];
            argbPixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    free_sd_image_generation_result(result);

    jintArray outArray = env->NewIntArray(totalPixels);
    if (outArray) {
        env->SetIntArrayRegion(outArray, 0, totalPixels, argbPixels.data());
    }
    return outArray;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_raunakpatil_roniepocket_imagegen_SdNative_edit(
    JNIEnv* env,
    jobject /* thiz */,
    jlong ctxHandle,
    jintArray initPixels,
    jint initWidth,
    jint initHeight,
    jstring prompt,
    jstring negativePrompt,
    jint steps,
    jfloat cfgScale,
    jint sampleMethod,
    jint scheduler,
    jfloat strength,
    jlong seed,
    jobject callback
) {
    RonieSdContext* wrapper = reinterpret_cast<RonieSdContext*>(ctxHandle);
    if (!wrapper || !wrapper->ctx || !initPixels) {
        LOGE("edit: null context or pixels");
        return nullptr;
    }
    sd_ctx_t* ctx = wrapper->ctx;

    jsize pixelCount = env->GetArrayLength(initPixels);
    if (pixelCount != initWidth * initHeight) {
        LOGE("edit: pixel count mismatch: expected %d, got %d", initWidth * initHeight, pixelCount);
        return nullptr;
    }

    jint* srcPixels = env->GetIntArrayElements(initPixels, nullptr);
    if (!srcPixels) return nullptr;

    std::vector<uint8_t> rgbBytes(initWidth * initHeight * 3);
    for (int i = 0; i < initWidth * initHeight; ++i) {
        jint argb = srcPixels[i];
        rgbBytes[i * 3 + 0] = static_cast<uint8_t>((argb >> 16) & 0xFF);
        rgbBytes[i * 3 + 1] = static_cast<uint8_t>((argb >> 8) & 0xFF);
        rgbBytes[i * 3 + 2] = static_cast<uint8_t>(argb & 0xFF);
    }
    env->ReleaseIntArrayElements(initPixels, srcPixels, JNI_ABORT);

    const char* promptStr = prompt ? env->GetStringUTFChars(prompt, nullptr) : "";
    const char* negPromptStr = negativePrompt ? env->GetStringUTFChars(negativePrompt, nullptr) : "";

    sd_img_gen_params_t gen_params;
    sd_img_gen_params_init(&gen_params);
    gen_params.prompt = promptStr;
    gen_params.negative_prompt = negPromptStr;
    gen_params.clip_skip = -1;
    gen_params.width = initWidth;
    gen_params.height = initHeight;
    gen_params.init_image.width = initWidth;
    gen_params.init_image.height = initHeight;
    gen_params.init_image.channel = 3;
    gen_params.init_image.data = rgbBytes.data();
    gen_params.strength = strength > 0.0f ? strength : 0.7f;
    gen_params.sample_params.sample_steps = steps;
    gen_params.sample_params.guidance.txt_cfg = cfgScale;

    if (sampleMethod >= 0 && sampleMethod < SAMPLE_METHOD_COUNT) {
        gen_params.sample_params.sample_method = static_cast<sample_method_t>(sampleMethod);
    } else {
        gen_params.sample_params.sample_method = EULER_A_SAMPLE_METHOD;
    }

    if (scheduler >= 0 && scheduler < SCHEDULER_COUNT) {
        gen_params.sample_params.scheduler = static_cast<scheduler_t>(scheduler);
    } else {
        gen_params.sample_params.scheduler = DISCRETE_SCHEDULER;
    }

    gen_params.seed = seed;
    gen_params.batch_count = 1;

    LOGI("Editing image: size=%dx%d, steps=%d, strength=%.2f, seed=%lld, callback=%p",
         initWidth, initHeight, steps, gen_params.strength, (long long)seed, callback);

    StepUserData udata{env, callback, nullptr, steps};
    if (callback) {
        jclass cbClass = env->GetObjectClass(callback);
        if (cbClass) {
            udata.onStepMethod = env->GetMethodID(cbClass, "onStep", "(II[III)V");
            if (udata.onStepMethod) {
                enum preview_t pmode = wrapper->hasTaesd ? PREVIEW_TAE : PREVIEW_PROJ;
                LOGI("Registering SD edit preview callback: mode=%d", (int)pmode);
                sd_set_preview_callback(preview_callback_bridge, pmode, 1, true, false, &udata);
            }
        }
    }

    sd_image_generation_result_t* result = nullptr;
    bool ok = generate_image(ctx, &gen_params, &result);

    if (callback && udata.onStepMethod) {
        sd_set_preview_callback(nullptr, PREVIEW_NONE, 0, false, false, nullptr);
    }

    if (prompt) env->ReleaseStringUTFChars(prompt, promptStr);
    if (negativePrompt) env->ReleaseStringUTFChars(negativePrompt, negPromptStr);

    if (!ok || !result || result->count == 0 || !result->items[0].image.data) {
        LOGE("edit generate_image failed or returned no images (ok=%d, result=%p)", ok, result);
        if (result) free_sd_image_generation_result(result);
        return nullptr;
    }

    const sd_image_t& img = result->items[0].image;
    uint32_t totalPixels = img.width * img.height;
    std::vector<jint> argbPixels(totalPixels);

    if (img.channel == 3) {
        for (uint32_t i = 0; i < totalPixels; ++i) {
            uint8_t r = img.data[i * 3 + 0];
            uint8_t g = img.data[i * 3 + 1];
            uint8_t b = img.data[i * 3 + 2];
            argbPixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
    } else if (img.channel == 4) {
        for (uint32_t i = 0; i < totalPixels; ++i) {
            uint8_t r = img.data[i * 4 + 0];
            uint8_t g = img.data[i * 4 + 1];
            uint8_t b = img.data[i * 4 + 2];
            uint8_t a = img.data[i * 4 + 3];
            argbPixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    free_sd_image_generation_result(result);

    jintArray outArray = env->NewIntArray(totalPixels);
    if (outArray) {
        env->SetIntArrayRegion(outArray, 0, totalPixels, argbPixels.data());
    }
    return outArray;
}

extern "C" JNIEXPORT void JNICALL
Java_com_raunakpatil_roniepocket_imagegen_HeavyWork_nativeTrimMemory(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    LOGI("Purging native heap pages back to OS via malloc_trim and mallopt");
#if defined(M_PURGE)
    mallopt(M_PURGE, 0);
#endif
    typedef int (*malloc_trim_fn)(size_t);
    static auto trim_fn = reinterpret_cast<malloc_trim_fn>(dlsym(RTLD_DEFAULT, "malloc_trim"));
    if (trim_fn) {
        trim_fn(0);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_raunakpatil_roniepocket_imagegen_SdNative_freeContext(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong ctxHandle
) {
    RonieSdContext* wrapper = reinterpret_cast<RonieSdContext*>(ctxHandle);
    if (wrapper) {
        if (wrapper->ctx) {
            LOGI("free_sd_ctx: %p", wrapper->ctx);
            free_sd_ctx(wrapper->ctx);
        }
        delete wrapper;
    }
}
