#!/usr/bin/env python3
"""Fetch hash-pinned public model/test fixtures for the optional Android Tiny tests."""
import argparse, hashlib, io, pathlib, tarfile, urllib.request, wave
REVISION = '65176e2deb88badc814a94058666cadccc29b61c'
FILES = {
    'tiny-encoder.int8.onnx': (12937772, 'd24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434'),
    'tiny-decoder.int8.onnx': (89855401, 'd2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925')}
def download(url, target, sha, max_bytes):
    if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == sha: return
    temp = target.with_suffix(target.suffix + '.part')
    try:
        digest, size = hashlib.sha256(), 0
        with urllib.request.urlopen(url, timeout=60) as src, temp.open('wb') as dst:
            while block := src.read(65536):
                size += len(block)
                if size > max_bytes: raise ValueError('Download exceeded bound')
                digest.update(block); dst.write(block)
        if digest.hexdigest() != sha: raise ValueError('Checksum mismatch')
        temp.replace(target)
    finally: temp.unlink(missing_ok=True)

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--destination', type=pathlib.Path, required=True)
    destination = parser.parse_args().destination; destination.mkdir(parents=True, exist_ok=True)
    for name, (size, sha) in FILES.items():
        download(f'https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/{REVISION}/{name}', destination/name, sha, size)
        assert (destination/name).stat().st_size == size
    archive = destination/'test-wavs.tar.bz2'
    download('https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/spoken-language-identification-test-wavs.tar.bz2', archive,
             '2b0f4f7cadb0313b1d2a8a9887e7af14dbdf8696083012bf1101c58f35af6a79', 20*1024*1024)
    with tarfile.open(archive) as tar:
        for name in ['de-german.wav', 'ar-arabic.wav']:
            member = next(m for m in tar.getmembers() if m.name.endswith('/'+name))
            with wave.open(io.BytesIO(tar.extractfile(member).read())) as source:
                assert source.getnchannels() == 1 and source.getsampwidth() == 2 and source.getframerate() == 16000
                with wave.open(str(destination/name),'wb') as output:
                    output.setparams(source.getparams()); output.writeframes(source.readframes(source.getnframes()))
    archive.unlink()
    print('PASS: pinned Whisper tiny int8 and public language fixtures')
if __name__ == '__main__': main()
