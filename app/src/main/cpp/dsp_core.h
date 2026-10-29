// Your Friend — Original project by Suvo.
// Plain C++ (no JNI) audio math, so it can be unit-tested on a PC. native_dsp.cpp wraps it for Java.
// Every function mirrors a Java reference implementation that stays in the app as the fallback:
//   visemes   -> com.suvo.agent.core.audio.Visemes.analyze
//   resample  -> Pcm.resample
//   normalizeGain -> Pcm.normalized (peak scan + gain)
//   rms16     -> VoiceGate.rms / PushToTalk level
#pragma once

#include <vector>

namespace yf {

// Mouth-shape weights per frame. Output layout: out[k * frames + f] for k = a, i, u, e, o.
// Returns the number of frames.
int visemes(const float* samples, int len, int rate, int hop, std::vector<float>& out);

// Linear-interpolation resampler. Output length is round(len * target / rate), at least 1.
std::vector<float> resample(const float* samples, int len, int rate, int target);

// Gain that brings quiet audio up to -1 dBFS (never more than 6x); 1.0 when nothing should change.
float normalizeGain(const float* samples, int len);

// Root-mean-square of 16-bit samples.
float rms16(const short* s, int n);

// Same, for little-endian 16-bit samples stored as bytes (n = byte count).
float rms16Bytes(const signed char* b, int n);

}  // namespace yf
