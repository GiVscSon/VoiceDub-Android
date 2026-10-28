/* VoiceDub v0.3 - browser dubbing of a pre-generated, direct audio URL.
 * No Yandex API or media extraction. Attachment is to the active HTML5 video only.
 * A selected remote track is loaded only after a trusted in-page user gesture.
 */
(() => {
  'use strict';
  if (window.__voicedubDubbing) return;

  let session = null;
  let generation = 0;
  let actionButton = null;
  const MAX_DRIFT = 0.6;
  const SOFT_DRIFT = 0.12;
  const RESET_DRIFT = 0.06;

  const clamp = (n, low, high) => Math.max(low, Math.min(high, n));
  const isTime = value => Number.isFinite(value) && value >= 0;

  function info(s) {
    if (!s) return {attached: false, state: 'idle'};
    const a = s.audio;
    const v = s.video;
    return {
      attached: true,
      state: s.state,
      videoId: s.videoId,
      videoPosition: isTime(v.currentTime) ? v.currentTime : null,
      audioPosition: isTime(a.currentTime) ? a.currentTime : null,
      driftSeconds: (isTime(a.currentTime) && isTime(v.currentTime)) ?
        Math.round((a.currentTime - (v.currentTime + s.offset)) * 1000) / 1000 : null,
      playbackRate: a.playbackRate,
      offsetSeconds: s.offset,
      audioReady: a.readyState >= 1,
      needsGesture: s.needsGesture
    };
  }

  function buttonText(s) {
    if (!actionButton) return;
    actionButton.textContent = !s ? 'VoiceDub' :
      s.state === 'playing' ? '■ Отключить озвучку' : '▶ Включить озвучку';
    actionButton.style.display = s ? '' : 'none';
  }

  function ensureButton() {
    if (actionButton || !document.body) return;
    actionButton = document.createElement('button');
    actionButton.id = 'voicedub-dubbing-action';
    actionButton.type = 'button';
    Object.assign(actionButton.style, {
      position: 'fixed', bottom: '72px', right: '12px', zIndex: '2147483647',
      font: 'bold 13px sans-serif', background: '#164ea6', color: '#fff',
      border: '0', borderRadius: '9px', padding: '11px 14px',
      boxShadow: '0 2px 12px #0006', cursor: 'pointer'
    });
    actionButton.addEventListener('click', event => {
      if (!event.isTrusted) return; // reject scripted clicks by page JavaScript
      const s = session;
      if (!s) return;
      if (s.enabled && !s.needsGesture) {
        detach();
      } else {
        s.enabled = true;
        s.needsGesture = false;
        if (!s.audio.src) {
          // No audio URL has been requested before a real page click.
          s.audio.src = s.url;
          s.audio.load();
        }
        synchronize(s, true); // trusted page gesture, not evaluateJavascript activation
      }
    });
    document.body.appendChild(actionButton);
    buttonText(session);
  }

  function sameVideo(s) {
    const probe = window.__voicedubProbe;
    if (!probe || typeof probe.currentVideo !== 'function') return false;
    const current = probe.currentVideo();
    // The same video element can change src in single-page applications.
    return current === s.video && !!s.video.isConnected &&
      (s.video.currentSrc || s.video.src || '') === s.videoSource &&
      (s.video.src || '') === s.videoPropertySrc &&
      (s.video.getAttribute?.('src') ?? null) === s.videoAttributeSrc;
  }

  function park(s, state) {
    try { s.audio.pause(); } catch (_) {}
    s.state = state;
    buttonText(s);
  }

  function synchronize(s, forceSeek = false) {
    if (s !== session) return;
    if (!sameVideo(s)) { detach(); return; }
    const v = s.video;
    const a = s.audio;
    if (!s.enabled) { park(s, 'armed'); return; }
    if (v.paused || v.ended || v.seeking || v.readyState < 2) {
      park(s, v.ended ? 'ended' : 'paused');
      return;
    }
    const desired = v.currentTime + s.offset;
    if (!isTime(desired) || desired < 0) { park(s, 'before-track'); return; }
    if (Number.isFinite(a.duration) && desired >= a.duration) {
      park(s, 'track-ended');
      return;
    }
    // Avoid an InvalidStateError when audio metadata has not been loaded yet.
    if (a.readyState < 1) { s.state = 'loading'; buttonText(s); return; }
    const drift = a.currentTime - desired;
    const nominalRate = clamp(v.playbackRate || 1, 0.5, 2.0);
    if (forceSeek || !Number.isFinite(drift) || Math.abs(drift) > MAX_DRIFT) {
      try { a.currentTime = desired; } catch (_) { s.state = 'seek-error'; return; }
      a.playbackRate = nominalRate;
    } else if (Math.abs(drift) >= SOFT_DRIFT) {
      // If audio is ahead, slow it; if it is behind, speed it up by 5%.
      a.playbackRate = clamp(nominalRate * (drift > 0 ? 0.95 : 1.05), 0.5, 2.0);
    } else if (Math.abs(drift) <= RESET_DRIFT || Math.abs(a.playbackRate - nominalRate) > 0.15) {
      a.playbackRate = nominalRate;
    }
    if (a.paused) {
      let promise;
      try { promise = a.play(); } catch (_) { s.needsGesture = true; s.state = 'gesture-required'; buttonText(s); return; }
      if (promise && typeof promise.then === 'function') {
        promise.then(() => {
          if (session === s) { s.needsGesture = false; s.state = 'playing'; buttonText(s); }
        }).catch(() => {
          if (session === s) { s.needsGesture = true; s.state = 'gesture-required'; buttonText(s); }
        });
      }
    } else { s.state = 'playing'; buttonText(s); }
  }

  function detach() {
    const s = session;
    session = null;
    generation++;
    if (!s) { buttonText(null); return {ok: true}; }
    if (s.timer !== null) window.clearInterval(s.timer);
    for (const [event, fn] of s.listeners) s.video.removeEventListener(event, fn);
    try { s.audio.pause(); s.audio.removeAttribute('src'); s.audio.load(); } catch (_) {}
    buttonText(null);
    return {ok: true};
  }

  function attach(input) {
    // Local file://, data:, and blob: schemes aren't accepted from arbitrary UI input.
    const urlString = String(input?.url || '').trim();
    let url;
    try { url = new URL(urlString); } catch (_) { return {ok: false, error: 'invalid-audio-url'}; }
    if (url.protocol !== 'https:') return {ok: false, error: 'https-required'};
    const probe = window.__voicedubProbe;
    const video = probe?.currentVideo?.();
    if (!video) return {ok: false, error: 'no-active-video'};
    const identity = probe.snapshot();
    const wanted = input?.videoId;
    if (typeof wanted === 'string' && wanted && wanted !== identity.videoId) {
      return {ok: false, error: 'video-changed'};
    }
    const offset = Number(input?.offsetSeconds ?? 0);
    if (!Number.isFinite(offset) || Math.abs(offset) > 300) return {ok: false, error: 'invalid-offset'};
    detach();
    const audio = new Audio();
    audio.preload = 'none'; // do not make a network request on attach()
    const s = {
      id: ++generation, video, videoSource: video.currentSrc || video.src || '',
      videoPropertySrc: video.src || '', videoAttributeSrc: video.getAttribute?.('src') ?? null,
      videoId: identity.videoId, audio, url: url.href, offset,
      enabled: false, needsGesture: true, state: 'armed', timer: null, listeners: []
    };
    session = s;
    function listen(event, fn) { video.addEventListener(event, fn); s.listeners.push([event, fn]); }
    for (const event of ['play', 'playing', 'pause', 'waiting', 'seeking', 'seeked', 'ratechange', 'timeupdate', 'ended']) {
      listen(event, () => synchronize(s, event === 'seeked' || event === 'ratechange'));
    }
    audio.addEventListener('loadedmetadata', () => synchronize(s, true));
    audio.addEventListener('error', () => {
      if (session === s) { park(s, 'audio-error'); s.enabled = false; }
    });
    s.timer = window.setInterval(() => synchronize(s), 250);
    ensureButton();
    buttonText(s);
    // The actual audio start is initiated by a user click in the webpage.
    return {ok: true, videoId: s.videoId, state: s.state, needsGesture: true};
  }

  window.__voicedubDubbing = Object.freeze({attach, detach, status: () => info(session)});
})();
