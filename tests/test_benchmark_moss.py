import importlib.util
import tempfile
import unittest
import wave
from pathlib import Path

spec = importlib.util.spec_from_file_location("bench", Path(__file__).parents[1] / "scripts" / "benchmark_moss_voice.py")
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


def wav(path):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b"\\x01\\x00" * 1600)


class BenchTest(unittest.TestCase):
    def test_fake_synth_validates_output_and_keeps_reference(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            ref = root / "ref.wav"
            out = root / "out.wav"
            wav(ref)
            (root / "infer_onnx.py").write_text(
                "import argparse, shutil\n"
                "p=argparse.ArgumentParser()\n"
                "p.add_argument('--execution-provider'); p.add_argument('--prompt-audio-path')\n"
                "p.add_argument('--text'); p.add_argument('--output-audio-path')\n"
                "a=p.parse_args(); shutil.copyfile(a.prompt_audio_path,a.output_audio_path)\n", encoding="utf-8")
            result = bench.run(root, ref, "Привет", out, 10)
            self.assertEqual(result["audio_seconds"], 0.1)
            self.assertTrue(ref.is_file())
            self.assertTrue(out.is_file())
            with self.assertRaises(ValueError):
                bench.run(root, ref, "Привет", ref, 10)

    def test_invalid_output_does_not_replace_existing(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); ref = root / "ref.wav"; out = root / "out.wav"
            wav(ref); out.write_bytes(b"previous")
            (root / "infer_onnx.py").write_text("pass\n", encoding="utf-8")
            with self.assertRaises((OSError, wave.Error)):
                bench.run(root, ref, "Тест", out, 10)
            self.assertEqual(out.read_bytes(), b"previous")


if __name__ == "__main__":
    unittest.main()
