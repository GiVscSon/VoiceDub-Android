# Experimental voice preservation

MOSS-TTS-Nano is a candidate for Russian synthesis using a reference voice. The upstream ONNX CLI supports direct reference WAV and has an Android demo with pre-tokenized examples; neither the Android live pipeline nor cloned voice quality has been validated in VoiceDub.

To benchmark on a PC after installing the upstream project and its ONNX dependencies:
`python scripts/benchmark_moss_voice.py --moss-dir PATH_TO_MOSS_TTS_NANO --reference REFERENCE.wav --text "Привет, это проверка перевода." --output OUTPUT.wav`

On first inference upstream may download model weights. The reported real-time factor includes model startup and is not streaming latency. Run only with an authorized voice reference. This is an offline one-shot benchmark, not an Android feature, source separation, speaker tracking, or live dubbing. Voice references and generated audio remain at user-chosen paths and are never committed.

Next validate Russian intelligibility, speaker similarity, CPU throughput and memory on target PC, then assess Android tokenizer, live segmentation, model distribution and end-to-end latency. Local/Yandex modes remain independent.

## Actual CPU experiment 2026-10-28

GitHub Actions run https://github.com/GiVscSon/VoiceDub-Android/actions/runs/40610886055 completed real MOSS ONNX inference using upstream sample en_2.wav and Russian test text. Reference 7.875 s, generated 4.4 s, elapsed 22.995 s including cold startup and model download, real-time factor including load 5.226. Artifact is stereo 48 kHz WAV. This does not measure steady-state speed or similarity, nor prove intelligibility, Android performance, speaker separation, or live dubbing. Test harness unit tests passed with a fake synthesizer. Android release remains without this model.
