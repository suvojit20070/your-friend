// Your Friend — Original project by Suvo.
// JNI wrappers for dsp_core (see there). Java: com.suvo.agent.nativebridge.NativeAudio.

#include <jni.h>

#include <vector>

#include "dsp_core.h"

namespace {

// Copies a Java float[] into a vector (JNI critical access would block the GC for long analyses).
bool readFloats(JNIEnv* env, jfloatArray arr, std::vector<float>& out) {
    if (arr == nullptr) return false;
    const jsize len = env->GetArrayLength(arr);
    out.resize(static_cast<size_t>(len));
    if (len > 0) env->GetFloatArrayRegion(arr, 0, len, out.data());
    return true;
}

jfloatArray toJava(JNIEnv* env, const std::vector<float>& v) {
    jfloatArray r = env->NewFloatArray(static_cast<jsize>(v.size()));
    if (r != nullptr && !v.empty()) env->SetFloatArrayRegion(r, 0, static_cast<jsize>(v.size()), v.data());
    return r;
}

}  // namespace

extern "C" {

// Returns float[5 * frames], laid out [a-frames..., i-frames..., u..., e..., o...].
JNIEXPORT jfloatArray JNICALL
Java_com_suvo_agent_nativebridge_NativeAudio_nativeVisemes(JNIEnv* env, jclass, jfloatArray samples, jint rate, jint hop) {
    std::vector<float> s;
    if (!readFloats(env, samples, s) || rate <= 0 || hop <= 0) return nullptr;
    std::vector<float> out;
    yf::visemes(s.data(), static_cast<int>(s.size()), rate, hop, out);
    return toJava(env, out);
}

JNIEXPORT jfloatArray JNICALL
Java_com_suvo_agent_nativebridge_NativeAudio_nativeResample(JNIEnv* env, jclass, jfloatArray samples, jint rate, jint target) {
    std::vector<float> s;
    if (!readFloats(env, samples, s) || s.empty() || rate <= 0 || target <= 0) return nullptr;
    return toJava(env, yf::resample(s.data(), static_cast<int>(s.size()), rate, target));
}

// Returns the normalised samples, or null when the audio is already loud enough (or silent).
JNIEXPORT jfloatArray JNICALL
Java_com_suvo_agent_nativebridge_NativeAudio_nativeNormalize(JNIEnv* env, jclass, jfloatArray samples) {
    std::vector<float> s;
    if (!readFloats(env, samples, s) || s.empty()) return nullptr;
    const float gain = yf::normalizeGain(s.data(), static_cast<int>(s.size()));
    if (gain == 1.f) return nullptr;
    for (float& v : s) v *= gain;
    return toJava(env, s);
}

JNIEXPORT jfloat JNICALL
Java_com_suvo_agent_nativebridge_NativeAudio_nativeRms16(JNIEnv* env, jclass, jshortArray samples, jint n) {
    if (samples == nullptr || n <= 0) return 0.f;
    const jsize len = env->GetArrayLength(samples);
    if (n > len) n = len;
    // Short, quick call: direct access without copying.
    jshort* p = static_cast<jshort*>(env->GetPrimitiveArrayCritical(samples, nullptr));
    if (p == nullptr) return 0.f;
    const float r = yf::rms16(reinterpret_cast<const short*>(p), n);
    env->ReleasePrimitiveArrayCritical(samples, p, JNI_ABORT);
    return r;
}

JNIEXPORT jfloat JNICALL
Java_com_suvo_agent_nativebridge_NativeAudio_nativeRms16Bytes(JNIEnv* env, jclass, jbyteArray bytes, jint n) {
    if (bytes == nullptr || n <= 1) return 0.f;
    const jsize len = env->GetArrayLength(bytes);
    if (n > len) n = len;
    jbyte* p = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(bytes, nullptr));
    if (p == nullptr) return 0.f;
    const float r = yf::rms16Bytes(reinterpret_cast<const signed char*>(p), n);
    env->ReleasePrimitiveArrayCritical(bytes, p, JNI_ABORT);
    return r;
}

}  // extern "C"
