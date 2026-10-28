"""Actual Chromium media event smoke test (optional). Requires Playwright and ffmpeg.

Uses generated local data-URI media on about:blank. The JS Audio constructor is
redirected to a local data URI, so network loading of an HTTPS audio file is NOT
validated. This environment blocks navigations even when Playwright intercepts them.
"""
import subprocess
import base64
import tempfile
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
PROBE = (ROOT/'app/src/main/assets/browser_probe.js').read_text()
DUB = (ROOT/'app/src/main/assets/browser_dubbing.js').read_text()

HTML = '''<!doctype html><html><head><meta charset="utf-8"></head><body>
<video id="movie" width="400" height="220" muted playsinline controls src="/movie.mp4"></video>
<button id="start" onclick="document.getElementById('movie').play()">play video</button>
</body></html>'''

with tempfile.TemporaryDirectory(prefix='voicedub-media-') as directory:
    movie = Path(directory)/'movie.mp4'
    dub = Path(directory)/'dub.wav'
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi',
        '-i','color=c=black:s=320x180:r=15','-t','8','-c:v','libx264',
        '-pix_fmt','yuv420p','-movflags','+faststart',str(movie)],check=True,timeout=20)
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi',
        '-i','sine=frequency=330:sample_rate=16000','-t','8',str(dub)],check=True,timeout=20)
    movie_uri = 'data:video/mp4;base64,' + base64.b64encode(movie.read_bytes()).decode()
    dub_uri = 'data:audio/wav;base64,' + base64.b64encode(dub.read_bytes()).decode()

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path='/usr/bin/chromium',headless=True,
            args=['--no-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required'])
        try:
            context=browser.new_context()
            page=context.new_page()
            page.goto('about:blank')
            page.set_content(HTML.replace('/movie.mp4', movie_uri),wait_until='domcontentloaded')
            # Redirect Audio remote source to a local WAV for browser event testing.
            page.evaluate("""(wav) => {
              const NativeAudio = window.Audio;
              window.Audio = function() {
                const media = new NativeAudio();
                Object.defineProperty(media, 'src', {
                  configurable: true,
                  get() { return media.getAttribute('src') || ''; },
                  set(_remoteValue) { media.setAttribute('src', wav); }
                });
                return media;
              };
            }""", dub_uri)
            page.evaluate(PROBE)
            page.evaluate(DUB)
            page.wait_for_function("document.getElementById('movie').readyState >= 2",timeout=13000)
            page.click('#start')
            page.wait_for_function("!document.getElementById('movie').paused",timeout=5000)
            result=page.evaluate("window.__voicedubDubbing.attach({url:'https://voicedub.test/dub.wav'})")
            assert result['ok'], result
            assert page.evaluate('window.__voicedubDubbing.status().state')=='armed'
            page.click('#voicedub-dubbing-action')
            page.wait_for_function("window.__voicedubDubbing.status().state === 'playing'",timeout=9000)
            page.wait_for_timeout(600)
            sync=page.evaluate('window.__voicedubDubbing.status()')
            assert abs(sync['driftSeconds']) < .65, sync
            page.evaluate('document.getElementById("movie").pause()')
            page.wait_for_function("window.__voicedubDubbing.status().state === 'paused'",timeout=5000)
            page.evaluate('document.getElementById("movie").currentTime=5')
            page.wait_for_function("document.getElementById('movie').currentTime > 4.9",timeout=4000)
            page.evaluate('document.getElementById("movie").playbackRate=1.5')
            page.evaluate('document.getElementById("movie").play()')
            page.wait_for_function("window.__voicedubDubbing.status().state === 'playing'",timeout=8000)
            sync=page.evaluate('window.__voicedubDubbing.status()')
            assert abs(sync['driftSeconds']) < .65, sync
            assert abs(sync['playbackRate']-1.5) <= .1, sync
            page.evaluate('document.getElementById("movie").src="/movie2.mp4"')
            page.wait_for_function("!window.__voicedubDubbing.status().attached",timeout=7000)
            print('PASS: Chromium HTML5 data-URI media events: attach, play/pause, seek, rate, drift, source replacement (HTTPS loading not tested)')
        finally:browser.close()
