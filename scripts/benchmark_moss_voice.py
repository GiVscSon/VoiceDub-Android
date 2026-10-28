#!/usr/bin/env python3
"""Measure a single MOSS-TTS-Nano ONNX voice-clone attempt; not a live service."""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import wave
from pathlib import Path


def wav_duration(path):
    with wave.open(str(path), "rb") as wav:
        if wav.getnframes() <= 0 or wav.getframerate() <= 0:
            raise ValueError("Empty WAV")
        return wav.getnframes() / wav.getframerate()


def run(moss_dir, reference, text, output, timeout=600):
    moss_dir = Path(moss_dir).resolve()
    reference = Path(reference).resolve(strict=True)
    output = Path(output).resolve()
    entry = moss_dir / "infer_onnx.py"
    if not entry.is_file():
        raise ValueError("MOSS-TTS-Nano infer_onnx.py not found")
    if output == reference:
        raise ValueError("Output must not overwrite the reference")
    if not text.strip():
        raise ValueError("Text is empty")
    reference_seconds = wav_duration(reference)
    output.parent.mkdir(parents=True, exist_ok=True)
    # A private temporary output prevents stale success or partial writes replacing a prior result.
    with tempfile.TemporaryDirectory(prefix=".voicedub-moss-", dir=str(output.parent)) as tmp:
        generated = Path(tmp) / "generated.wav"
        cmd = [sys.executable, str(entry), "--execution-provider", "cpu",
               "--prompt-audio-path", str(reference), "--text", text,
               "--output-audio-path", str(generated)]
        start = time.monotonic()
        subprocess.run(cmd, cwd=str(moss_dir), check=True, timeout=timeout)
        elapsed = time.monotonic() - start
        duration = wav_duration(generated)
        os.replace(generated, output)
    return {"reference_seconds": round(reference_seconds, 3),
            "audio_seconds": round(duration, 3), "wall_seconds": round(elapsed, 3),
            "realtime_factor_including_load": round(elapsed / duration, 3),
            "output": str(output)}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--moss-dir", required=True)
    p.add_argument("--reference", required=True, help="Consented speaker reference WAV")
    p.add_argument("--text", required=True, help="Russian text to synthesize")
    p.add_argument("--output", required=True)
    p.add_argument("--timeout", type=int, default=600)
    args = p.parse_args()
    try:
        print(json.dumps(run(args.moss_dir, args.reference, args.text, args.output, args.timeout), ensure_ascii=False))
    except (OSError, ValueError, wave.Error, subprocess.SubprocessError) as exc:
        print("Voice clone benchmark failed: " + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
