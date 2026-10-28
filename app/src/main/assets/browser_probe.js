/* VoiceDub browser probe v0.2: local DOM detection only; no network or audio interception. */
(() => {
  'use strict';
  if (window.__voicedubProbe) return;

  const videos = new Set();
  const watchedRoots = new WeakSet();
  let scanTimer = null;
  let badge = null;

  function onMutation(records) {
    // Ignore our own badge: its text and position are cosmetic.
    if (records.every(m => m.target && (m.target.id === 'voicedub-video-marker' || m.target.closest?.('#voicedub-video-marker')))) return;
    if (scanTimer !== null) return;
    scanTimer = window.setTimeout(() => {
      scanTimer = null;
      rescan();
    }, 250);
  }

  function observe(root) {
    if (!root || watchedRoots.has(root)) return;
    watchedRoots.add(root);
    const observer = new MutationObserver(onMutation);
    observer.observe(root, {childList: true, subtree: true, attributes: true, attributeFilter: ['src']});
  }

  function visit(root) {
    if (!root) return;
    observe(root);
    for (const v of root.querySelectorAll?.('video') || []) videos.add(v);
    for (const node of root.querySelectorAll?.('*') || []) {
      if (node.shadowRoot) visit(node.shadowRoot); // open shadow roots only
      if (node.tagName === 'IFRAME') {
        try { if (node.contentDocument) visit(node.contentDocument); } catch (_) { /* cross-origin */ }
      }
    }
  }

  function rescan() {
    for (const v of videos) if (!v.isConnected) videos.delete(v);
    visit(document);
  }

  function videoId(video) {
    let href = location.href;
    let host = location.hostname;
    try {
      const win = video.ownerDocument.defaultView;
      if (win && win.location) { href = win.location.href; host = win.location.hostname; }
    } catch (_) { /* cross-origin */ }
    let url;
    try { url = new URL(href); } catch (_) { return { id: '', platform: 'unknown' }; }
    const path = url.pathname;
    const match = (pattern) => path.match(pattern);
    if (/(^|\.)youtube\.com$|(^|\.)youtube-nocookie\.com$/.test(host)) {
      const id = url.searchParams.get('v') || match(/^\/(?:shorts|embed|live)\/([a-zA-Z0-9_-]+)/)?.[1];
      if (id) return { id: `youtube:${id}`, platform: 'YouTube' };
    }
    if (host === 'youtu.be') return { id: `youtube:${path.slice(1)}`, platform: 'YouTube' };
    if (/(^|\.)vimeo\.com$/.test(host)) {
      const id = match(/\/(?:video\/)?([0-9]+)(?:\/|$)/)?.[1];
      if (id) return { id: `vimeo:${id}`, platform: 'Vimeo' };
    }
    if (/(^|\.)(?:vk\.com|vk\.ru|vkvideo\.ru)$/.test(host)) {
      const id = match(/\/(video-?\d+_\d+)/)?.[1] || url.searchParams.get('z')?.match(/video-?\d+_\d+/)?.[0];
      if (id) return { id: `vk:${id}`, platform: 'VK Видео' };
    }
    // blob: and MSE sources are local browser handles, not remotely accessible media URLs.
    const src = video.currentSrc || video.src || video.querySelector('source')?.src || '';
    const srcType = src.startsWith('blob:') ? 'blob/MSE' : src ? 'direct' : 'unknown';
    return { id: `page:${url.origin}${url.pathname}`, platform: host || 'web', srcType };
  }

  // Short, nonreversible fingerprint for detecting player-source replacements.
  function fingerprint(value) {
    let h = 2166136261;
    for (let i = 0; i < value.length; i++) {
      h ^= value.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return (h >>> 0).toString(16);
  }

  function activeVideo() {
    let best = null, score = -1;
    for (const v of videos) {
      if (!v.isConnected) continue;
      const r = v.getBoundingClientRect();
      const clippedWidth = Math.max(0, Math.min(innerWidth, r.right) - Math.max(0, r.left));
      const clippedHeight = Math.max(0, Math.min(innerHeight, r.bottom) - Math.max(0, r.top));
      const area = clippedWidth * clippedHeight;
      if (area < 400) continue;
      const points = area + (!v.paused && !v.ended ? 5000000 : 0) + (v.readyState > 1 ? 1000 : 0);
      if (points > score) { best = v; score = points; }
    }
    return best;
  }

  function drawBadge(video) {
    if (!badge && document.body) {
      badge = document.createElement('div');
      badge.id = 'voicedub-video-marker';
      badge.textContent = 'VoiceDub · видео обнаружено';
      Object.assign(badge.style, {
        position: 'fixed', zIndex: '2147483646', padding: '5px 9px',
        borderRadius: '7px', font: '12px sans-serif', color: '#fff',
        background: 'rgba(17,24,39,.85)', pointerEvents: 'none'
      });
      document.body.appendChild(badge);
    }
    if (!badge) return;
    if (!video) { badge.style.display = 'none'; return; }
    const rect = video.getBoundingClientRect();
    badge.style.display = '';
    badge.style.left = `${Math.max(4, Math.min(innerWidth - 188, rect.right - 187))}px`;
    badge.style.top = `${Math.max(4, rect.top + 5)}px`;
  }

  window.__voicedubProbe = Object.freeze({
    snapshot() {
      const video = activeVideo();
      drawBadge(video);
      if (!video) return {found: false, count: videos.size, reason: 'no-visible-html-video'};
      const identity = videoId(video);
      const source = video.currentSrc || video.src || '';
      return {
        found: true, count: videos.size,
        platform: identity.platform, videoId: identity.id,
        sourceType: identity.srcType || (source.startsWith('blob:') ? 'blob/MSE' : source ? 'direct' : 'unknown'),
        paused: video.paused, seeking: video.seeking, ended: video.ended,
        playerKey: `${identity.id}|${fingerprint(video.src || video.currentSrc || '')}`,
        position: Number.isFinite(video.currentTime) ? Math.round(video.currentTime * 10) / 10 : 0,
        duration: Number.isFinite(video.duration) ? Math.round(video.duration * 10) / 10 : null
      };
    },
    // Explicit lifecycle functions for future timestamped subtitle and dubbing engines.
    currentVideo: () => activeVideo(),
    dispose: () => { if (badge) badge.remove(); badge = null; }
  });
  rescan();
  window.setInterval(() => {
    if (document.visibilityState === 'visible') rescan();
  }, 5000); // Some frameworks swap Shadow DOM without observing old roots.
})();
