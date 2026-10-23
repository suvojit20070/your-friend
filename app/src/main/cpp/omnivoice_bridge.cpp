// Your Friend — Original project by Suvo.
//
// Bridge to audio.cpp (https://github.com/0xShug0/audio.cpp, Apache-2.0) for running the OmniVoice
// TTS model in-process. The audio.cpp library is NOT bundled: the user supplies libaudiocpp.so
// (built for Android with the experimental workflow in .github/workflows/audiocpp-android.yml) and an
// OmniVoice GGUF model. We load it with dlopen() and resolve the documented C ABI (include/audiocpp.h,
// ABI 0.x) with dlsym(), so the app builds and runs without it.
//
// Only the declarations we use are reproduced below, matching audiocpp.h ABI 0.2.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "YF.OmniVoice"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" {
typedef enum audiocpp_status {
    AUDIOCPP_OK = 0,
    AUDIOCPP_ERR_INVALID_ARGUMENT = 1,
    AUDIOCPP_ERR_UNSUPPORTED_FAMILY = 2,
    AUDIOCPP_ERR_LOAD_FAILED = 3,
    AUDIOCPP_ERR_RUNTIME = 4,
    AUDIOCPP_ERR_OUT_OF_MEMORY = 5,
    AUDIOCPP_ERR_OUT_OF_RANGE = 6,
    AUDIOCPP_ERR_NOT_AVAILABLE = 7
} audiocpp_status;

typedef struct audiocpp_registry audiocpp_registry;
typedef struct audiocpp_model audiocpp_model;
typedef struct audiocpp_session audiocpp_session;
typedef struct audiocpp_options audiocpp_options;
typedef struct audiocpp_request audiocpp_request;
typedef struct audiocpp_result audiocpp_result;

typedef struct audiocpp_model_config {
    const char* family_hint;
    const char* config_id;
    const char* weight_id;
    const char* model_spec_override;
} audiocpp_model_config;

typedef struct audiocpp_backend_config {
    const char* backend;
    int device;
    int threads;
} audiocpp_backend_config;
}

namespace {

typedef uint32_t (*fn_abi_version)(void);
typedef const char* (*fn_build_version)(void);
typedef const char* (*fn_last_error)(void);
typedef audiocpp_status (*fn_registry_create)(const char*, audiocpp_registry**);
typedef void (*fn_registry_free)(audiocpp_registry*);
typedef audiocpp_status (*fn_model_load)(audiocpp_registry*, const char*, const audiocpp_model_config*,
                                         const audiocpp_options*, audiocpp_model**);
typedef void (*fn_model_free)(audiocpp_model*);
typedef const char* (*fn_model_family)(const audiocpp_model*);
typedef int (*fn_model_supports)(const audiocpp_model*, const char*, const char*);
typedef audiocpp_status (*fn_session_create)(const audiocpp_model*, const char*, const char*,
                                             const audiocpp_backend_config*, const audiocpp_options*,
                                             audiocpp_session**);
typedef void (*fn_session_free)(audiocpp_session*);
typedef audiocpp_status (*fn_session_run)(audiocpp_session*, const audiocpp_request*, audiocpp_result**);
typedef audiocpp_request* (*fn_request_create)(void);
typedef void (*fn_request_free)(audiocpp_request*);
typedef audiocpp_status (*fn_request_set_text)(audiocpp_request*, const char*, const char*);
typedef audiocpp_status (*fn_request_set_voice_audio)(audiocpp_request*, const float*, size_t, int, int);
typedef audiocpp_status (*fn_request_set_option)(audiocpp_request*, const char*, const char*);
typedef void (*fn_result_free)(audiocpp_result*);
typedef audiocpp_status (*fn_result_audio)(const audiocpp_result*, const float**, size_t*, int*, int*);

struct Engine {
    void* lib = nullptr;
    std::mutex mu;
    std::string lastError;
    int lastRate = 0;

    fn_abi_version abi_version = nullptr;
    fn_build_version build_version = nullptr;
    fn_last_error last_error = nullptr;
    fn_registry_create registry_create = nullptr;
    fn_registry_free registry_free = nullptr;
    fn_model_load model_load = nullptr;
    fn_model_free model_free = nullptr;
    fn_model_family model_family = nullptr;
    fn_model_supports model_supports = nullptr;
    fn_session_create session_create = nullptr;
    fn_session_free session_free = nullptr;
    fn_session_run session_run = nullptr;
    fn_request_create request_create = nullptr;
    fn_request_free request_free = nullptr;
    fn_request_set_text request_set_text = nullptr;
    fn_request_set_voice_audio request_set_voice_audio = nullptr;
    fn_request_set_option request_set_option = nullptr;
    fn_result_free result_free = nullptr;
    fn_result_audio result_audio = nullptr;

    audiocpp_registry* registry = nullptr;
    audiocpp_model* model = nullptr;
    audiocpp_session* session = nullptr;

    void setError(const std::string& where) {
        std::string detail = last_error ? std::string(last_error()) : std::string();
        lastError = where + (detail.empty() ? "" : ": " + detail);
        LOGW("%s", lastError.c_str());
    }

    void releaseModel() {
        if (session && session_free) session_free(session);
        if (model && model_free) model_free(model);
        session = nullptr;
        model = nullptr;
    }
};

template <typename T>
bool resolve(Engine* e, T& out, const char* name) {
    out = reinterpret_cast<T>(dlsym(e->lib, name));
    if (!out) {
        e->lastError = std::string("Symbol not found in library: ") + name;
        return false;
    }
    return true;
}

std::string str(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

Engine* engine(jlong h) { return reinterpret_cast<Engine*>(h); }

}  // namespace

extern "C" {

/** dlopen()s a dependency library with RTLD_GLOBAL so libaudiocpp.so can resolve it. */
JNIEXPORT jstring JNICALL
Java_com_suvo_agent_nativebridge_OmniVoiceNative_nativePreload(JNIEnv* env, jclass, jstring path) {
    std::string p = str(env, path);
    void* h = dlopen(p.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (h == nullptr) {
        const char* err = dlerror();
        return env->NewStringUTF(err ? err : "dlopen failed");
    }
    return nullptr;
}

JNIEXPORT jlong JNICALL
Java_com_suvo_agent_nativebridge_OmniVoiceNative_nativeOpen(JNIEnv* env, jclass, jstring libPath, jobjectArray errOut) {
    auto* e = new Engine();
    std::string p = str(env, libPath);
    e->lib = dlopen(p.c_str(), RTLD_NOW | RTLD_LOCAL);
    auto fail = [&](const std::string& msg) -> jlong {
        if (errOut != nullptr && env->GetArrayLength(errOut) > 0) {
            env->SetObjectArrayElement(errOut, 0, env->NewStringUTF(msg.c_str()));
        }
        if (e->lib) dlclose(e->lib);
        delete e;
        return 0;
    };
    if (e->lib == nullptr) {
        const char* err = dlerror();
        return fail(std::string("Could not load library: ") + (err ? err : "unknown error"));
    }
    bool ok = resolve(e, e->abi_version, "audiocpp_abi_version") &&
              resolve(e, e->build_version, "audiocpp_build_version") &&
              resolve(e, e->last_error, "audiocpp_last_error") &&
              resolve(e, e->registry_create, "audiocpp_registry_create") &&
              resolve(e, e->registry_free, "audiocpp_registry_free") &&
              resolve(e, e->model_load, "audiocpp_model_load") &&
              resolve(e, e->model_free, "audiocpp_model_free") &&
              resolve(e, e->model_family, "audiocpp_model_family") &&
              resolve(e, e->model_supports, "audiocpp_model_supports") &&
              resolve(e, e->session_create, "audiocpp_session_create") &&
              resolve(e, e->session_free, "audiocpp_session_free") &&
              resolve(e, e->session_run, "audiocpp_session_run") &&
              resolve(e, e->request_create, "audiocpp_request_create") &&
              resolve(e, e->request_free, "audiocpp_request_free") &&
              resolve(e, e->request_set_text, "audiocpp_request_set_text") &&
              resolve(e, e->request_set_voice_audio, "audiocpp_request_set_voice_audio") &&
              resolve(e, e->request_set_option, "audiocpp_request_set_option") &&
              resolve(e, e->result_free, "audiocpp_result_free") &&
              resolve(e, e->result_audio, "audiocpp_result_audio");
    if (!ok) return fail(e->lastError + " (is this the audio.cpp C API library?)");
    const uint32_t abi = e->abi_version();
    if ((abi >> 16) != 0u) {
        char buf[128];
        snprintf(buf, sizeof(buf), "Unsupported audio.cpp ABI %u.%u (this app supports 0.x)", abi >> 16, (abi >> 8) & 0xff);
        return fail(buf);
    }
    if (e->registry_create(nullptr, &e->registry) != AUDIOCPP_OK) {
        e->setError("registry_create failed");
        return fail(e->lastError);
    }
    LOGI("audio.cpp %s loaded (ABI %u.%u)", e->build_version(), abi >> 16, (abi >> 8) & 0xff);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jstring JNICALL
Java_com_suvo_agent_nativebridge_OmniVoiceNative_nativeVersion(JNIEnv* env, jclass, jlong h) {
    Engine* e = engine(h);
    if (!e) return nullptr;
    const uint32_t abi = e->abi_version();
    char buf[160];
    snprintf(buf, sizeof(buf), "audio.cpp %s (ABI %u.%u)", e->build_version(), abi >> 16, (abi >> 8) & 0xff);
    return env->NewStringUTF(buf);
}

JNIEXPORT jboolean JNICALL
Java_com_suvo_agent_nativebridge_OmniVoiceNative_nativeLoadModel(JNIEnv* env, jclass, jlong h, jstring modelPath,
                                                                 jstring family, jint threads) {
    Engine* e = engine(h);
    if (!e) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(e->mu);
    e->releaseModel();
    std::string path = str(env, modelPath);
    std::string fam = str(env, family);
    audiocpp_model_config cfg{fam.empty() ? nullptr : fam.c_str(), nullptr, nullptr, nullptr};
    if (e->model_load(e->registry, path.c_str(), &cfg, nullptr, &e->model) != AUDIOCPP_OK || e->model == nullptr) {
        e->setError("Model load failed");
        e->model = nullptr;
        return JNI_FALSE;
    }
    if (!e->model_supports(e->model, "tts", "offline")) {
        e->lastError = std::string("Model family '") + e->model_family(e->model) + "' does not support offline TTS";
        e->releaseModel();
        return JNI_FALSE;
    }
    audiocpp_backend_config backend{"cpu", 0, threads > 0 ? threads : 4};
    if (e->session_create(e->model, "tts", "offline", &backend, nullptr, &e->session) != AUDIOCPP_OK || e->session == nullptr) {
        e->setError("Session creation failed");
        e->releaseModel();
        return JNI_FALSE;
    }
    LOGI("Model loaded: %s", e->model_family(e->model));
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_suvo_agent_nativebridge_OmniVoiceNative_nativeModelFamily(JNIEnv* env, jclass, jlong h) {
    Engine* e = engine(h);
    if (!e || !e->model) return nullptr;
    return env->NewStringUTF(e->model_family(e->model));
}

JNIEXPORT jfloatArray JNICALL
Java_com_suvo_agent_nativebridge_OmniVoiceNative_nativeSynthesize(JNIEnv* env, jclass, jlong h, jstring text, jstring language,
                                                                  jfloatArray refAudio, jint refRate, jstring refText,
                                                                  jstring instruct, jfloat speed, jint steps) {
    Engine* e = engine(h);
    if (!e) return nullptr;
    std::lock_guard<std::mutex> lock(e->mu);
    if (!e->session) {
        e->lastError = "No model loaded";
        return nullptr;
    }
    std::string t = str(env, text);
    std::string lang = str(env, language);
    std::string rt = str(env, refText);
    std::string ins = str(env, instruct);
    audiocpp_request* req = e->request_create();
    if (!req) {
        e->lastError = "request_create failed";
        return nullptr;
    }
    jfloatArray out = nullptr;
    audiocpp_result* res = nullptr;
    do {
        if (e->request_set_text(req, t.c_str(), lang.empty() ? nullptr : lang.c_str()) != AUDIOCPP_OK) {
            e->setError("set_text failed");
            break;
        }
        if (refAudio != nullptr && refRate > 0) {
            jsize n = env->GetArrayLength(refAudio);
            std::vector<float> ref(static_cast<size_t>(n));
            env->GetFloatArrayRegion(refAudio, 0, n, ref.data());
            if (e->request_set_voice_audio(req, ref.data(), ref.size(), refRate, 1) != AUDIOCPP_OK) {
                e->setError("set_voice_audio failed");
                break;
            }
            if (!rt.empty()) e->request_set_option(req, "reference_text", rt.c_str());
        }
        if (!ins.empty()) e->request_set_option(req, "instruct", ins.c_str());
        char buf[32];
        if (speed > 0.f) {
            snprintf(buf, sizeof(buf), "%.3f", static_cast<double>(speed));
            e->request_set_option(req, "speed", buf);
        }
        if (steps > 0) {
            snprintf(buf, sizeof(buf), "%d", static_cast<int>(steps));
            e->request_set_option(req, "num_inference_steps", buf);
        }
        if (e->session_run(e->session, req, &res) != AUDIOCPP_OK || res == nullptr) {
            e->setError("Synthesis failed");
            break;
        }
        const float* samples = nullptr;
        size_t frames = 0;
        int rate = 0, channels = 0;
        if (e->result_audio(res, &samples, &frames, &rate, &channels) != AUDIOCPP_OK || samples == nullptr || frames == 0) {
            e->setError("The model returned no audio");
            break;
        }
        std::vector<float> mono(frames);
        const int ch = channels > 0 ? channels : 1;
        for (size_t i = 0; i < frames; ++i) {
            float acc = 0.f;
            for (int c = 0; c < ch; ++c) acc += samples[i * ch + c];
            mono[i] = acc / ch;
        }
        e->lastRate = rate;
        out = env->NewFloatArray(static_cast<jsize>(frames));
        if (out) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(frames), mono.data());
    } while (false);
    if (res) e->result_free(res);
    e->request_free(req);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_suvo_agent_nativebridge_OmniVoiceNative_nativeSampleRate(JNIEnv*, jclass, jlong h) {
    Engine* e = engine(h);
    return e ? e->lastRate : 0;
}

JNIEXPORT jstring JNICALL
Java_com_suvo_agent_nativebridge_OmniVoiceNative_nativeLastError(JNIEnv* env, jclass, jlong h) {
    Engine* e = engine(h);
    if (!e) return env->NewStringUTF("engine not open");
    return env->NewStringUTF(e->lastError.c_str());
}

JNIEXPORT void JNICALL
Java_com_suvo_agent_nativebridge_OmniVoiceNative_nativeClose(JNIEnv*, jclass, jlong h) {
    Engine* e = engine(h);
    if (!e) return;
    {
        std::lock_guard<std::mutex> lock(e->mu);
        e->releaseModel();
        if (e->registry && e->registry_free) e->registry_free(e->registry);
        e->registry = nullptr;
    }
    // The library stays loaded: unloading code that may own threads is unsafe.
    delete e;
}

}  // extern "C"
