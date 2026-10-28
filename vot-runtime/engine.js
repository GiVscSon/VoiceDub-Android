import VOTClient from '@vot.js/core';

const pending = new Map();
let counter = 0;
const to64 = bytes => { let s = ''; for (const b of bytes) s += String.fromCharCode(b); return btoa(s); };
const from64 = value => Uint8Array.from(atob(value), c => c.charCodeAt(0));
const fetchNative = async (url, options = {}) => {
  const id = String(++counter);
  const body = options.body instanceof Blob ? new Uint8Array(await options.body.arrayBuffer()) : new Uint8Array(options.body || []);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('network-timeout')); }, 35000);
    pending.set(id, { resolve, reject, timer });
    VotNative.request(id, String(url), JSON.stringify(options.headers || {}), to64(body));
  });
};
globalThis.__votNetworkResult = (id, status, data) => {
  const job = pending.get(id);
  if (!job) return;
  pending.delete(id); clearTimeout(job.timer);
  if (status < 100) { job.reject(new Error('network-failed')); return; }
  job.resolve(new Response(from64(data), { status }));
};
const client = new VOTClient({fetchFn: fetchNative, requestLang:'en', responseLang:'ru'});
globalThis.__votTranslate = async request => {
  try {
    const result = await client.translateVideo({
      videoData: {url: request.url, videoId: request.videoId, host: request.host, duration: request.duration},
      requestLang: request.language, responseLang: 'ru',
      extraOpts: {useLivelyVoice: request.lively === true}, shouldSendFailedAudio: false,
    });
    VotNative.result(request.job, JSON.stringify({ok:true, ...result}));
  } catch (_) { VotNative.result(request.job, JSON.stringify({ok:false,error:'vot-request-failed'})); }
};
VotNative.ready();
