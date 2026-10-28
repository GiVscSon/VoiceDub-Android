#!/usr/bin/env python3
"""Minimal project preflight; not a substitute for aapt, javac, Kotlin or on-device checks."""
import pathlib
import sys
import xml.etree.ElementTree as ET
from collections import Counter
root = pathlib.Path(__file__).resolve().parent.parent
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
android = '{http://schemas.android.com/apk/res/android}'
perms = [e.attrib[android+'name'] for e in manifest.findall('uses-permission')]
duplicates = [k for k, v in Counter(perms).items() if v != 1]
assert not duplicates, f'Duplicate manifest permissions: {duplicates}'
for name in ('android.permission.RECORD_AUDIO','android.permission.INTERNET',
             'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION'):
    assert name in perms, f'Missing permission {name}'
service = manifest.find('application').find('service')
assert service.attrib.get(android+'foregroundServiceType') == 'mediaProjection'
assert service.attrib.get(android+'exported') == 'false'
for script in ('browser_probe.js','browser_dubbing.js','browser_captions.js'):
    assert (root/'app/src/main/assets'/script).is_file()
workflow = (root/'.github/workflows/android-debug.yml').read_text()
assert 'assembleDebug' in workflow and 'upload-artifact' in workflow
assert 'test' in workflow
print('PASS: Manifest XML / permissions / assets / Android workflow')
