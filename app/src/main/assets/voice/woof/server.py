# Your Friend — Original project by Suvo.
#
# Tiny local voice server for the Your Friend app: Underdog Woof (ConwayResearch/Underdog-Woof,
# Underdog Non-Commercial License 1.0 — "Voice by Underdog Woof") on this phone, in Termux.
# Listens on 127.0.0.1 only, token protected. POST /tts {"text","voice","seed"} -> audio/wav.
import io
import json
import os
import sys
import threading
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
PORT = int(os.environ.get("YF_PORT", "8766"))
TOKEN = os.environ.get("YF_TOKEN", "")

import numpy as np  # noqa: E402
from underdog_woof import Woof  # noqa: E402

_models = {}
_lock = threading.Lock()  # one synthesis at a time: the phone's CPU is shared


def model(voice):
    voice = voice if voice in ("juno", "mira") else "juno"
    if voice not in _models:
        _models[voice] = Woof(voice=voice)
    return _models[voice]


def to_wav(audio, sr):
    a = np.asarray(audio, dtype=np.float32).reshape(-1)
    pcm = (np.clip(a, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(int(sr))
        w.writeframes(pcm)
    return buf.getvalue()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _ok_token(self):
        if TOKEN and self.headers.get("x-token") != TOKEN:
            self._send(401, b'{"error":"bad token"}')
            return False
        return True

    def do_GET(self):
        if not self._ok_token():
            return
        if self.path.startswith("/health"):
            self._send(200, json.dumps({"ok": True, "loaded": sorted(_models)}).encode())
        else:
            self._send(404, b'{"error":"unknown"}')

    def do_POST(self):
        if not self._ok_token():
            return
        if not self.path.startswith("/tts"):
            self._send(404, b'{"error":"unknown"}')
            return
        try:
            n = int(self.headers.get("Content-Length", "0"))
            req = json.loads(self.rfile.read(n) or b"{}")
            text = str(req.get("text", "")).strip()
            if not text:
                self._send(400, b'{"error":"text is required"}')
                return
            with _lock:
                m = model(str(req.get("voice", "juno")))
                audio = m(text, seed=int(req.get("seed", 0)))
                sr = getattr(m, "SR", None) or getattr(m, "sr", None) or 24000
            self._send(200, to_wav(audio, sr), "audio/wav")
        except Exception as e:  # report, keep serving
            self._send(500, json.dumps({"error": "%s: %s" % (type(e).__name__, e)}).encode())


if __name__ == "__main__":
    model(os.environ.get("YF_VOICE", "juno"))  # load once at start, so the first reply is quick
    print("woof on", PORT, flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
