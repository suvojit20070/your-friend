# Your Friend — Original project by Suvo.
#
# Underdog Woof (ConwayResearch/Underdog-Woof, Underdog Non-Commercial License 1.0 — "Voice by
# Underdog Woof") inside the app, through Chaquopy's Python. The model files are downloaded by the
# app into one folder; this module loads them once and turns text into 16-bit mono WAV bytes.
import io
import sys
import threading
import wave

import numpy as np

_lock = threading.Lock()
_models = {}
_dir = None


def _model(model_dir, voice):
    global _dir
    if _dir != model_dir:
        if model_dir not in sys.path:
            sys.path.insert(0, model_dir)
        _dir = model_dir
        _models.clear()
    voice = voice if voice in ("juno", "mira") else "juno"
    if voice not in _models:
        from underdog_woof import Woof
        _models[voice] = Woof(voice=voice)
    return _models[voice]


def load(model_dir, voice):
    """Loads the model (slow the first time). Returns True."""
    with _lock:
        _model(model_dir, voice)
    return True


def unload():
    """Frees the loaded voices."""
    with _lock:
        _models.clear()
    return True


def synth(model_dir, text, voice, seed):
    """Text -> WAV bytes (mono, 16-bit, the model's sample rate)."""
    with _lock:
        m = _model(model_dir, voice)
        audio = m(text, seed=int(seed))
        sr = getattr(m, "SR", None) or getattr(m, "sr", None) or 24000
    a = np.asarray(audio, dtype=np.float32).reshape(-1)
    pcm = (np.clip(a, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(int(sr))
        w.writeframes(pcm)
    return buf.getvalue()
