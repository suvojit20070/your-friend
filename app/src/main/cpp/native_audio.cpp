// Your Friend — Original project by Suvo.
// Native lip-sync analysis. Mirrors com.suvo.agent.core.audio.LipSync (the Java reference and
// fallback) but runs several times faster on long utterances.

#include <jni.h>
#include <algorithm>
#include <cmath>
#include <vector>

namespace {

double goertzel(const float* x, int start, int end, double freq, int rate) {
    const int n = end - start;
    if (n <= 0) return 0.0;
    const double k = std::round(n * freq / rate);
    const double w = 2.0 * M_PI * k / n;
    const double coeff = 2.0 * std::cos(w);
    double s1 = 0.0, s2 = 0.0;
    for (int i = start; i < end; ++i) {
        const double s0 = x[i] + coeff * s1 - s2;
        s2 = s1;
        s1 = s0;
    }
    return (s1 * s1 + s2 * s2 - coeff * s1 * s2) / n;
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_suvo_agent_nativebridge_NativeAudio_nativeAnalyze(JNIEnv* env, jclass, jfloatArray samplesArr,
                                                          jint rate, jdouble fps) {
    if (samplesArr == nullptr || rate <= 0 || fps <= 0) return nullptr;
    const jsize len = env->GetArrayLength(samplesArr);
    std::vector<float> s(static_cast<size_t>(len));
    if (len > 0) env->GetFloatArrayRegion(samplesArr, 0, len, s.data());

    const int hop = std::max(1, static_cast<int>(std::lround(rate / fps)));
    const int frames = std::max(1, (static_cast<int>(len) + hop - 1) / hop);
    const int win = hop * 2;
    std::vector<float> rms(frames), low(frames), high(frames);
    float maxRms = 1e-6f;
    for (int f = 0; f < frames; ++f) {
        const int start = f * hop;
        const int end = std::min(static_cast<int>(len), start + win);
        double sum = 0.0;
        for (int i = start; i < end; ++i) sum += static_cast<double>(s[i]) * s[i];
        const int n = std::max(1, end - start);
        rms[f] = static_cast<float>(std::sqrt(sum / n));
        maxRms = std::max(maxRms, rms[f]);
        low[f] = static_cast<float>(goertzel(s.data(), start, end, 500, rate) + goertzel(s.data(), start, end, 800, rate));
        high[f] = static_cast<float>(goertzel(s.data(), start, end, 2200, rate) + goertzel(s.data(), start, end, 3000, rate));
    }

    // Output layout: [open0, form0, open1, form1, ...]
    std::vector<float> out(static_cast<size_t>(frames) * 2);
    const float noise = maxRms * 0.06f;
    float prevOpen = 0.f, prevForm = 0.5f;
    for (int f = 0; f < frames; ++f) {
        float v = std::max(0.f, rms[f] - noise) / std::max(1e-6f, maxRms - noise);
        v = static_cast<float>(std::pow(v, 0.6));
        float o = v > prevOpen ? prevOpen + (v - prevOpen) * 0.75f : prevOpen + (v - prevOpen) * 0.35f;
        const float total = low[f] + high[f];
        float fm = total <= 1e-9f ? prevForm : high[f] / total;
        fm = prevForm + (std::min(1.f, std::max(0.f, fm * 1.6f)) - prevForm) * 0.4f;
        o = std::min(1.f, std::max(0.f, o));
        out[static_cast<size_t>(f) * 2] = o;
        out[static_cast<size_t>(f) * 2 + 1] = fm;
        prevOpen = o;
        prevForm = fm;
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}
