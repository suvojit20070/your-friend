// Native audio processing for Your Friend.
// Heavy per-frame work (runs on every ~10-20ms audio chunk during calls / lip-sync)
// is done here in C++ for speed: RMS/peak, energy VAD, mouth-open smoothing, and
// a fast linear resample used when feeding STT.
//
// JNI class: com.suvo.agent.core.audio.NativeAudio

#include <jni.h>
#include <cstdint>
#include <cmath>
#include <vector>
#include <algorithm>

extern "C" {

// ---- RMS (0..1) of 16-bit PCM ----
JNIEXPORT jfloat JNICALL
Java_com_suvo_agent_core_audio_NativeAudio_rms(JNIEnv* env, jclass, jshortArray pcm, jint len) {
    if (len <= 0) return 0.f;
    jshort* buf = env->GetShortArrayElements(pcm, nullptr);
    double sum = 0.0;
    for (int i = 0; i < len; ++i) { double v = buf[i]; sum += v * v; }
    env->ReleaseShortArrayElements(pcm, buf, JNI_ABORT);
    double rms = std::sqrt(sum / (double)len) / 32768.0;
    return (jfloat)std::min(1.0, rms);
}

// ---- peak (0..1) ----
JNIEXPORT jfloat JNICALL
Java_com_suvo_agent_core_audio_NativeAudio_peak(JNIEnv* env, jclass, jshortArray pcm, jint len) {
    if (len <= 0) return 0.f;
    jshort* buf = env->GetShortArrayElements(pcm, nullptr);
    int p = 0;
    for (int i = 0; i < len; ++i) { int a = std::abs((int)buf[i]); if (a > p) p = a; }
    env->ReleaseShortArrayElements(pcm, buf, JNI_ABORT);
    return (jfloat)(p / 32768.0);
}

// ---- energy VAD: 1 if this frame is speech, else 0 ----
// threshold is a normalized RMS (e.g. 0.012). Simple, fast, no model.
JNIEXPORT jint JNICALL
Java_com_suvo_agent_core_audio_NativeAudio_isVoiced(JNIEnv* env, jclass, jshortArray pcm, jint len, jfloat threshold) {
    if (len <= 0) return 0;
    jshort* buf = env->GetShortArrayElements(pcm, nullptr);
    double sum = 0.0;
    for (int i = 0; i < len; ++i) { double v = buf[i]; sum += v * v; }
    env->ReleaseShortArrayElements(pcm, buf, JNI_ABORT);
    double rms = std::sqrt(sum / (double)len) / 32768.0;
    return rms > threshold ? 1 : 0;
}

// ---- mouth-open value for lip-sync (0..1), smoothed toward target amplitude ----
// prev = last mouth value, amp = current rms. Exponential smoothing + mild gain.
JNIEXPORT jfloat JNICALL
Java_com_suvo_agent_core_audio_NativeAudio_mouthOpen(JNIEnv*, jclass, jfloat prev, jfloat amp) {
    float target = std::min(1.0f, amp * 3.2f);           // gain so quiet speech still moves
    float a = target > prev ? 0.55f : 0.30f;             // open fast, close a bit slower
    return prev + (target - prev) * a;
}

// ---- fast linear resample of 16-bit PCM (e.g. 48000 -> 16000 for STT) ----
JNIEXPORT jshortArray JNICALL
Java_com_suvo_agent_core_audio_NativeAudio_resample(JNIEnv* env, jclass, jshortArray pcm, jint len, jint inRate, jint outRate) {
    if (len <= 0 || inRate <= 0 || outRate <= 0) return env->NewShortArray(0);
    jshort* buf = env->GetShortArrayElements(pcm, nullptr);
    long outLen = (long)len * outRate / inRate;
    std::vector<jshort> out(outLen);
    double step = (double)len / (double)outLen;
    double pos = 0.0;
    for (long i = 0; i < outLen; ++i) {
        long idx = (long)pos;
        double frac = pos - idx;
        jshort a = buf[std::min((long)len - 1, idx)];
        jshort b = buf[std::min((long)len - 1, idx + 1)];
        out[i] = (jshort)(a + (b - a) * frac);
        pos += step;
    }
    env->ReleaseShortArrayElements(pcm, buf, JNI_ABORT);
    jshortArray result = env->NewShortArray((jsize)outLen);
    env->SetShortArrayRegion(result, 0, (jsize)outLen, out.data());
    return result;
}

} // extern "C"
