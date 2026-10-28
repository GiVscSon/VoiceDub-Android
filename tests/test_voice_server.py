import base64
import importlib.util
import io
import json
import threading
import unittest
import urllib.request
import urllib.error
import wave
from pathlib import Path

spec = importlib.util.spec_from_file_location("server", Path(__file__).parents[1] / "companion" / "voice_server.py")
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


def reference():
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes(bytes([0, 8]) * 16000 * 3)
    return out.getvalue()


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.wav = reference()
        self.http = server.make_server("127.0.0.1", 0, lambda text, ref: self.wav)
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        self.url = "http://127.0.0.1:%d" % self.http.server_port

    def tearDown(self):
        self.http.shutdown(); self.http.server_close(); self.thread.join()

    def post(self, data):
        req = urllib.request.Request(self.url + "/v1/synthesize", json.dumps(data).encode(),
                                     {"Content-Type": "application/json"})
        return urllib.request.urlopen(req, timeout=5)

    def test_round_trip(self):
        with urllib.request.urlopen(self.url + "/health") as res:
            self.assertTrue(json.load(res)["ready"])
        with self.post({"text": "Привет", "language": "ru", "reference_wav": base64.b64encode(self.wav).decode()}) as res:
            self.assertEqual(res.headers["Content-Type"], "audio/wav")
            self.assertEqual(res.read(), self.wav)

    def test_rejects_bad_audio(self):
        for data in [{"text": "x", "language": "ru", "reference_wav": "!!!"},
                     {"text": "", "language": "ru", "reference_wav": base64.b64encode(self.wav).decode()},
                     {"text": "x", "language": "en", "reference_wav": base64.b64encode(self.wav).decode()}]:
            with self.assertRaises(urllib.error.HTTPError) as err:
                self.post(data)
            self.assertEqual(err.exception.code, 400)

    def test_rejects_parallel_backlog(self):
        started = threading.Event(); release = threading.Event()
        self.http.shutdown(); self.http.server_close(); self.thread.join()
        def slow(text, ref):
            started.set(); release.wait(3); return self.wav
        self.http = server.make_server("127.0.0.1", 0, slow)
        self.url = "http://127.0.0.1:%d" % self.http.server_port
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True); self.thread.start()
        data = {"text": "x", "language": "ru", "reference_wav": base64.b64encode(self.wav).decode()}
        def first():
            with self.post(data) as res: res.read()
        t = threading.Thread(target=first); t.start()
        self.assertTrue(started.wait(2))
        try:
            with self.assertRaises(urllib.error.HTTPError) as err: self.post(data)
            self.assertEqual(err.exception.code, 429)
        finally:
            release.set(); t.join()


if __name__ == "__main__":
    unittest.main()
