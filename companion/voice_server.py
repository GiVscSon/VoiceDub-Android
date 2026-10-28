#!/usr/bin/env python3
"""VoiceDub's local voice server. Model is loaded once; reference files live only during a request."""
import argparse
import base64
import io
import json
import sys
import tempfile
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BODY = 800_000


def validate_request(body):
    if not isinstance(body, dict) or body.get("language") != "ru":
        raise ValueError("Only Russian synthesis is supported")
    text = body.get("text")
    if not isinstance(text, str) or not text.strip() or len(text) > 1000:
        raise ValueError("Expected 1..1000 text characters")
    encoded = body.get("reference_wav")
    if not isinstance(encoded, str) or len(encoded) > 700_000:
        raise ValueError("Invalid voice reference")
    raw = base64.b64decode(encoded, validate=True)
    with wave.open(io.BytesIO(raw), "rb") as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2 or w.getframerate() != 16000:
            raise ValueError("Reference must be mono PCM16 at 16000 Hz")
        seconds = w.getnframes() / 16000
        if not 3 <= seconds <= 10:
            raise ValueError("Reference must contain 3..10 seconds")
        if len(w.readframes(w.getnframes())) != w.getnframes() * 2:
            raise ValueError("Truncated reference")
    return text, raw


class MossSynthesizer:
    def __init__(self, moss_dir, threads=4):
        sys.path.insert(0, str(Path(moss_dir).resolve()))
        from onnx_tts_runtime import OnnxTtsRuntime
        self.runtime = OnnxTtsRuntime(thread_count=threads, execution_provider="cpu", max_new_frames=375,
                                      sample_mode="fixed", do_sample=True)

    def __call__(self, text, reference):
        with tempfile.TemporaryDirectory(prefix="voicedub-voice-") as tmp:
            ref = Path(tmp) / "reference.wav"
            output = Path(tmp) / "speech.wav"
            ref.write_bytes(reference)
            self.runtime.synthesize(text=text, prompt_audio_path=str(ref), output_audio_path=str(output),
                                    sample_mode="fixed", streaming=True, enable_wetext=False)
            return output.read_bytes()


def make_server(host, port, synthesize):
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Never log text, voice samples, client addresses or headers.

        def setup(self):
            super().setup()
            self.connection.settimeout(15)

        def reply(self, code, data, content_type="application/json"):
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            if self.path != "/health":
                self.reply(404, b'{"error":"not_found"}')
                return
            self.reply(200, b'{"ready":true,"engine":"moss-onnx","language":"ru"}')

        def do_POST(self):
            if self.path != "/v1/synthesize":
                self.reply(404, b'{"error":"not_found"}')
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 1 <= length <= MAX_BODY or self.headers.get("Transfer-Encoding"):
                    self.reply(413, b'{"error":"body_limit"}')
                    return
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise ValueError("Truncated request")
                text, reference = validate_request(json.loads(raw))
            except (ValueError, TypeError, wave.Error, EOFError):
                self.reply(400, b'{"error":"invalid_text_or_reference"}')
                return
            # Reject overload instead of building an unbounded queue of old speech.
            if not lock.acquire(blocking=False):
                self.reply(429, b'{"error":"busy"}')
                return
            try:
                start = time.monotonic()
                audio = synthesize(text, reference)
                if len(audio) <= 44 or len(audio) > 16 * 1024 * 1024:
                    raise ValueError("Invalid model output")
                with wave.open(io.BytesIO(audio), "rb") as wav:
                    if wav.getnframes() <= 0:
                        raise ValueError("Empty model output")
                print(json.dumps({"event": "synthesis", "seconds": round(time.monotonic() - start, 3)}), flush=True)
                self.reply(200, audio, "audio/wav")
            except Exception:
                self.reply(500, b'{"error":"synthesis_failed"}')
            finally:
                lock.release()

    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    return server


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--moss-dir", required=True)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--threads", type=int, default=4)
    args = p.parse_args()
    model = MossSynthesizer(args.moss_dir, args.threads)
    server = make_server(args.host, args.port, model)
    print("VoiceDub MOSS server ready", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
