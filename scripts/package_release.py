#!/usr/bin/env python3
"""Validate the real release APK before making it a user-facing artifact."""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parent.parent
tools = pathlib.Path(os.environ['ANDROID_HOME']) / 'build-tools' / '35.0.0'
apk = root / 'app/build/outputs/apk/tiny/release/app-tiny-release.apk'
debug_apk = root / 'app/build/outputs/apk/tiny/debug/app-tiny-debug.apk'

def certificate(path):
    result = subprocess.check_output(
        [str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(path)], text=True)
    print(result)
    found = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)', result)
    if not found:
        raise RuntimeError('No signer certificate digest')
    return found.group(1).lower()

cert = certificate(apk)
assert cert == certificate(debug_apk), 'Release would break updates of the installed preview'
assert cert == '337a9cf416a9ceb6d48248ebfeda7a92cf604c18220356b8750aac557ec05efe'
badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
assert 'application-debuggable' not in badging, 'Release must not be debuggable'
package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
assert package and package.group(1) == 'com.voicedub.app.preview'
configuration = (root / 'app/build.gradle.kts').read_text()
version = re.search(r'versionName = "([^"]+)"', configuration).group(1)
version_code = re.search(r'versionCode = (\d+)', configuration).group(1)
assert package.group(2) == version_code and package.group(3) == version
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    abis = sorted({name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')})
    assert set(abis) == {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}, 'Universal APK is incomplete'
    assert any('sherpa' in name and name.endswith('.so') for name in archive.namelist()), 'Tiny runtime missing'

destination = root / 'release'
destination.mkdir(exist_ok=True)
name = f'VoiceDub-{version}.apk'
shutil.copyfile(apk, destination / name)
notes = f'RELEASE-{version}.md'
shutil.copyfile(root / notes, destination / notes)
with apk.open('rb') as source:
    digest = hashlib.file_digest(source, 'sha256').hexdigest()
(destination / 'SHA256SUMS.txt').write_text(f'{digest}  {name}\n')
validation = {
    'version': version, 'versionCode': int(version_code), 'package': package.group(1),
    'debuggable': False, 'sha256': digest, 'size_bytes': apk.stat().st_size,
    'certificate_sha256': cert, 'abis': abis, 'commit': os.environ.get('GITHUB_SHA'),
    'signing_scope': 'Existing sideload compatibility certificate; not a Play Store identity',
}
(destination / 'validation.json').write_text(json.dumps(validation, indent=2) + '\n')
if os.environ.get('GITHUB_OUTPUT'):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'version={version}\n')
print(json.dumps(validation))
