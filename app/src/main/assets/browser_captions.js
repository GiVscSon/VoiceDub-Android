/* VoiceDub browser captions v0.4: live text from the app's PCM translation pipeline.
 * Text is displayed with textContent (never HTML); timestamps are presentation times,
 * not original speech-alignment timestamps. No network requests or video extraction.
 */
(() => {
  'use strict';
  if (window.__voicedubCaptions) return;
  let overlay = null;
  let entry = null;
  let attachedVideo = null;
  let videoSource = '';
  let lastSession = null;
  let lastSequence = -1;
  const LIFETIME = 6.0;

  function sourceOf(video) { return (video?.src || '') + '|' + (video?.currentSrc || ''); }
  function ensure() {
    if (!document.body) return null;
    if (!overlay || !overlay.isConnected) {
      overlay = document.createElement('div');
      overlay.id = 'voicedub-captions';
      overlay.setAttribute('aria-live', 'polite');
      Object.assign(overlay.style, {
        position: 'fixed', bottom: '10%', left: '5%', width: '90%',
        zIndex: '2147483645', pointerEvents: 'none', color: '#fff',
        textAlign: 'center', font: 'bold 19px/1.5 sans-serif',
        textShadow: '0 2px 4px #000, 0 1px 12px #000',
        whiteSpace: 'pre-line', display: 'none'
      });
      document.body.appendChild(overlay);
    }
    return overlay;
  }
  function clear() {
    entry = null;
    if (overlay) { overlay.textContent = ''; overlay.style.display = 'none'; }
  }
  function update() {
    if (!entry) return;
    const video = window.__voicedubProbe?.currentVideo?.();
    if (!video || video !== attachedVideo || !video.isConnected ||
        sourceOf(video) !== videoSource || video.seeking || video.ended || video.paused ||
        !Number.isFinite(video.currentTime) || video.currentTime < entry.at - 0.5 ||
        video.currentTime > entry.at + LIFETIME) {
      clear(); return;
    }
    const el = ensure();
    if (el) { el.textContent = entry.translated; el.style.display = ''; }
  }
  function push(payload) {
    const video = window.__voicedubProbe?.currentVideo?.();
    if (!video || video.paused || video.ended || !Number.isFinite(video.currentTime)) {
      return {ok: false, reason: 'no-playing-video'};
    }
    const translated = String(payload?.translated || '').trim().slice(0, 500);
    const original = String(payload?.original || '').trim().slice(0, 500);
    const session = String(payload?.session || '');
    const sequence = Number(payload?.sequence);
    if (!translated || !Number.isSafeInteger(sequence) || sequence < 0 || !session) {
      return {ok: false, reason: 'invalid-caption'};
    }
    if (session === lastSession && sequence <= lastSequence) {
      return {ok: false, reason: 'stale-caption'};
    }
    lastSession = session;
    lastSequence = sequence;
    attachedVideo = video;
    videoSource = sourceOf(video);
    entry = {translated, original, at: video.currentTime};
    update();
    return {ok: true, position: entry.at};
  }
  window.__voicedubCaptions = Object.freeze({push, clear, status: () => ({
    active: !!entry, translated: entry?.translated || '', at: entry?.at ?? null
  })});
  window.setInterval(update, 250);
})();
