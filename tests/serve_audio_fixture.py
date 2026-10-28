"""CI transport fixture only: returns a real model-generated WAV, no model inference here."""
import importlib.util
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("server", root / "companion" / "voice_server.py")
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
audio = Path(sys.argv[1]).read_bytes()
server = module.make_server("0.0.0.0", 8765, lambda text, reference: audio)
server.serve_forever()
