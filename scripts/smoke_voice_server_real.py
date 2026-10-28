#!/usr/bin/env python3
"""Real model -> HTTP -> WAV smoke. Uses only the upstream demo voice, never user recordings."""
import argparse
import base64
import importlib.util
import json
import threading
import time
import urllib.request
import wave
from pathlib import Path


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--moss-dir", required=True)
    p.add_argument("--output-dir", required=True)
    args = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location("voice_server", root / "companion" / "voice_server.py")
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    import numpy as np
    destination = Path(args.output_dir).resolve(); destination.mkdir(parents=True, exist_ok=True)
    reference = destination / "reference-16k.wav"
    with wave.open(str(Path(args.moss_dir) / "assets/audio/en_2.wav"), "rb") as w:
        assert w.getsampwidth() == 2
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").reshape(-1, w.getnchannels()).mean(axis=1)
        samples = min(8 * 16000, int(len(pcm) * 16000 / w.getframerate()))
        mono = np.interp(np.arange(samples) * w.getframerate() / 16000, np.arange(len(pcm)), pcm).astype("<i2")
    with wave.open(str(reference), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(mono.tobytes())
    start = time.monotonic()
    model = module.MossSynthesizer(args.moss_dir)
    load_seconds = time.monotonic() - start
    server = module.make_server("127.0.0.1", 0, model)
    t = threading.Thread(target=server.serve_forever, daemon=True); t.start()
    results = []
    try:
        for i in range(2):
            body = json.dumps({"text": "Здравствуйте, это проверка русского перевода с сохранением голоса.",
                               "language": "ru", "reference_wav": base64.b64encode(reference.read_bytes()).decode()}).encode()
            request = urllib.request.Request("http://127.0.0.1:%d/v1/synthesize" % server.server_port, body,
                                              {"Content-Type": "application/json"})
            start = time.monotonic()
            with urllib.request.urlopen(request, timeout=180) as response:
                assert response.headers["Content-Type"] == "audio/wav"
                audio = response.read()
            elapsed = time.monotonic() - start
            output = destination / ("http-cloned-voice-%d.wav" % (i + 1)); output.write_bytes(audio)
            with wave.open(str(output), "rb") as w:
                duration = w.getnframes() / w.getframerate()
                assert duration > 0
                results.append({"request": i + 1, "seconds": round(elapsed, 3), "audio_seconds": round(duration, 3),
                                "rtf": round(elapsed / duration, 3), "sample_rate": w.getframerate()})
    finally:
        server.shutdown(); server.server_close(); t.join()
    report = {"model_load_seconds": round(load_seconds, 3), "http_requests": results,
              "reference": "upstream en_2 demo, resampled mono PCM16 16k", "quality_evaluated": False}
    (destination / "http-metrics.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
