param([string]$BindAddress = "127.0.0.1", [int]$Port = 8765)
$ErrorActionPreference = "Stop"
$taskRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $taskRoot
if (!(Test-Path ".venv/Scripts/python.exe")) {
    & py -3.11 -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw "Install Python 3.11 for Windows, then run this script again." }
}
$taskPython = Join-Path $taskRoot ".venv/Scripts/python.exe"
$runtimeDir = Join-Path $taskRoot "MOSS-TTS-Nano"
if (!(Test-Path "$runtimeDir/infer_onnx.py")) {
    $taskCommit = "8b7bcc9341b3b4ef3a3a58ba1338a7d85ff133eb"
    Invoke-WebRequest "https://github.com/OpenMOSS/MOSS-TTS-Nano/archive/$taskCommit.zip" -OutFile "moss-runtime.zip"
    Expand-Archive "moss-runtime.zip" -DestinationPath $taskRoot -Force
    Move-Item "MOSS-TTS-Nano-$taskCommit" $runtimeDir
    Remove-Item "moss-runtime.zip"
}
if (!(Test-Path ".venv/voicedub-ready")) {
    & $taskPython -m pip install torch==2.7.0 torchaudio==2.7.0 --index-url https://download.pytorch.org/whl/cpu
    if ($LASTEXITCODE -ne 0) { throw "CPU PyTorch installation failed" }
    & $taskPython -m pip install -r "$runtimeDir/requirements.txt" huggingface_hub
    if ($LASTEXITCODE -ne 0) { throw "Model dependencies installation failed" }
    Set-Content ".venv/voicedub-ready" "installed"
}
Write-Host "First launch downloads model weights. Wait for 'VoiceDub MOSS server ready'."
& $taskPython voice_server.py --moss-dir $runtimeDir --host $BindAddress --port $Port
if ($LASTEXITCODE -ne 0) { throw "Voice server stopped with an error" }
