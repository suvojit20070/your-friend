// Your Friend — Original project by Suvo.
#include "dsp_core.h"

#include <algorithm>
#include <cmath>

namespace yf {

namespace {

const float VOWELS[5][2] = {
        {850, 1450},  // a
        {320, 2600},  // i
        {360, 950},   // u
        {520, 2150},  // e
        {560, 1000},  // o
};

const double kPi = 3.14159265358979323846;

// Goertzel power for several frequencies in ONE pass over the samples. Each frequency keeps its own
// recurrence, so the result is bit-identical to running them one by one; the inner loop over the
// frequencies is independent work the compiler turns into SIMD instructions.
void goertzelMany(const float* x, int n, const double* freq, int count, int rate, double* power) {
    std::vector<double> coeff(static_cast<size_t>(count)), s1(static_cast<size_t>(count), 0.0), s2(static_cast<size_t>(count), 0.0);
    for (int k = 0; k < count; k++) coeff[k] = 2 * std::cos(2 * kPi * freq[k] / rate);
    double* a1 = s1.data();
    double* a2 = s2.data();
    const double* cf = coeff.data();
    for (int i = 0; i < n; i++) {
        const double xi = x[i];
        for (int k = 0; k < count; k++) {
            const double s0 = xi + cf[k] * a1[k] - a2[k];
            a2[k] = a1[k];
            a1[k] = s0;
        }
    }
    for (int k = 0; k < count; k++) power[k] = a1[k] * a1[k] + a2[k] * a2[k] - cf[k] * a1[k] * a2[k];
}

// Frequency of the strongest region in [lo, hi] (energy-weighted around the maximum).
float peak(const float* x, int n, int rate, float lo, float hi, float step, std::vector<double>& e) {
    const int count = static_cast<int>((hi - lo) / step) + 1;
    if (count <= 1) return lo;
    std::vector<double> freq(static_cast<size_t>(count));
    for (int i = 0; i < count; i++) freq[i] = lo + i * step;
    e.assign(static_cast<size_t>(count), 0.0);
    goertzelMany(x, n, freq.data(), count, rate, e.data());
    int best = 0;
    for (int i = 0; i < count; i++) {
        if (e[i] > e[best]) best = i;
    }
    double num = 0, den = 0;
    for (int i = std::max(0, best - 4); i <= std::min(count - 1, best + 4); i++) {
        const double wgt = e[i] * e[i];
        num += wgt * (lo + i * step);
        den += wgt;
    }
    return den <= 0 ? lo + best * step : static_cast<float>(num / den);
}

void score(float f1, float f2, float* s) {
    float sum = 0;
    for (int k = 0; k < 5; k++) {
        const double d1 = std::log(static_cast<double>(f1 / VOWELS[k][0])) / 0.22;
        const double d2 = std::log(static_cast<double>(f2 / VOWELS[k][1])) / 0.20;
        s[k] = static_cast<float>(std::exp(-(d1 * d1 + d2 * d2) / 2));
        sum += s[k];
    }
    if (sum < 1e-6f) {
        for (int k = 0; k < 5; k++) s[k] = 0.2f;
        return;
    }
    for (int k = 0; k < 5; k++) s[k] /= sum;
}

}  // namespace

int visemes(const float* samples, int len, int rate, int hop, std::vector<float>& out) {
    if (hop < 1) hop = 1;
    const int frames = std::max(1, (len + hop - 1) / hop);
    out.assign(static_cast<size_t>(5) * frames, 0.f);
    const int win = std::max(hop * 2, rate / 40);  // at least 25 ms, enough to see formants
    std::vector<float> w(static_cast<size_t>(win));
    for (int i = 0; i < win; i++) w[i] = static_cast<float>(0.5 - 0.5 * std::cos(2 * kPi * i / (win - 1)));
    std::vector<float> buf(static_cast<size_t>(win));
    std::vector<double> scratch;
    float prev[5] = {0.2f, 0.2f, 0.2f, 0.2f, 0.2f};
    for (int f = 0; f < frames; f++) {
        const int start = std::max(0, f * hop + hop / 2 - win / 2);
        const int n = std::max(0, std::min(win, len - start));
        double energy = 0;
        for (int i = 0; i < win; i++) {
            const float s = i < n ? samples[start + i] : 0.f;
            buf[i] = s * w[i];
            energy += buf[i] * buf[i];
        }
        float cur[5];
        for (int k = 0; k < 5; k++) cur[k] = prev[k];
        if (energy > 1e-7 * win) {
            // Fine grids: the voice is a comb of pitch harmonics, a coarse grid can miss the peak.
            const float f1 = peak(buf.data(), win, rate, 250, 1050, 20, scratch);
            const float f2 = peak(buf.data(), win, rate, std::max(700.f, f1 + 250), 3000, 30, scratch);
            score(f1, f2, cur);
        }
        // Fast attack, slower release, like real articulation.
        for (int k = 0; k < 5; k++) {
            const float rateK = cur[k] > prev[k] ? 0.55f : 0.3f;
            prev[k] = prev[k] + (cur[k] - prev[k]) * rateK;
            out[static_cast<size_t>(k) * frames + f] = prev[k];
        }
    }
    return frames;
}

std::vector<float> resample(const float* samples, int len, int rate, int target) {
    if (len <= 0 || rate <= 0 || target <= 0) return std::vector<float>();
    const long long scaled = static_cast<long long>(std::floor(static_cast<double>(len) * target / rate + 0.5));
    const int n = static_cast<int>(std::max<long long>(1, scaled));
    std::vector<float> out(static_cast<size_t>(n));
    const double step = static_cast<double>(rate) / target;
    for (int i = 0; i < n; i++) {
        const double pos = i * step;
        const int i0 = static_cast<int>(pos);
        const int i1 = std::min(i0 + 1, len - 1);
        const double frac = pos - i0;
        out[i] = static_cast<float>(samples[std::min(i0, len - 1)] * (1 - frac) + samples[i1] * frac);
    }
    return out;
}

float normalizeGain(const float* samples, int len) {
    float peakV = 0;
    for (int i = 0; i < len; i++) peakV = std::max(peakV, std::fabs(samples[i]));
    if (peakV <= 0 || peakV >= 0.89f) return 1.f;
    return std::min(6.f, 0.89f / peakV);
}

float rms16(const short* s, int n) {
    double sum = 0;
    for (int i = 0; i < n; i++) sum += static_cast<double>(s[i]) * s[i];
    return static_cast<float>(std::sqrt(sum / std::max(1, n)));
}

float rms16Bytes(const signed char* b, int n) {
    double sum = 0;
    for (int i = 0; i + 1 < n; i += 2) {
        const short s = static_cast<short>((b[i] & 0xff) | (b[i + 1] << 8));
        sum += static_cast<double>(s) * s;
    }
    return static_cast<float>(std::sqrt(sum / std::max(1, n / 2)));
}

}  // namespace yf
