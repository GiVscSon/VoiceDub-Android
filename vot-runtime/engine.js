import VOTClient from '@vot.js/core';

// This module is loaded only by VotEngine's network-isolated WebView.
const pending = new Map();
let counter = 0;
let active = null;
const CHUNK_BYTES = 5295308;
const to64 = bytes => {
  const parts = [];
  for (let i = 0; i < bytes.length; i += 32768) parts.push(String.fromCharCode(...bytes.subarray(i, i + 32768)));
  return btoa(parts.join(''));
};
const from64 = value => Uint8Array.from(atob(value), c => c.charCodeAt(0));
const current = state => active === state && !state.cancelled;
const emit = (state, value) => { if (current(state)) VotNative.result(state.request.job, JSON.stringify(value)); };
const fetchNative = state => async (url, options = {}) => {
  if (!current(state)) throw new Error('cancelled');
  const id = String(++counter);
  const body = options.body instanceof Blob ? new Uint8Array(await options.body.arrayBuffer()) : new Uint8Array(options.body || []);
  if (!current(state)) throw new Error('cancelled');
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('network-timeout')); }, 35000);
    pending.set(id, { resolve, reject, timer, state });
    VotNative.request(state.request.job, id, String(options.method || 'POST'), String(url), JSON.stringify(options.headers || {}), to64(body));
  });
};
globalThis.__votNetworkResult = (id, status, data) => {
  const job = pending.get(id);
  if (!job) return;
  pending.delete(id); clearTimeout(job.timer);
  if (!current(job.state)) { job.reject(new Error('cancelled')); return; }
  if (status !== 200) { job.reject(new Error(status >= 100 ? `http-${status}` : 'network-failed')); return; }
  job.resolve(new Response(from64(data), { status }));
};
function fail(state, error, stage) {
  const detail = error?.data;
  const marker = typeof detail?.data === 'string' ? detail.data.match(/^http-(\d{3})$/) : null;
  const status = marker ? Number(marker[1]) : 0;
  const kind = detail?.status === 7 ? 'auth_required' : status === 401 || status === 403 ? 'http_auth' :
    status === 429 ? 'rate_limit' : status >= 500 ? 'server' : status ? 'http' :
    detail?.success === false ? 'network' : error?.message === 'source-unavailable' ? 'source_unavailable' : 'service';
  emit(state, {ok:false, error:kind, stage: error?.message === 'Failed to request create session' ? 'session' : stage, httpStatus:status});
}
globalThis.__votCancel = () => {
  if (active) active.cancelled = true;
  active = null;
  for (const item of pending.values()) { clearTimeout(item.timer); item.reject(new Error('cancelled')); }
  pending.clear();
};
globalThis.__votTranslate = async request => {
  if (!active || active.request.job !== request.job) {
    globalThis.__votCancel();
    const state = {request, cancelled:false, busy:false, first:true, translationId:null, uploaded:false};
    active = state;
    state.client = new VOTClient({fetchFn:fetchNative(state), requestLang:'auto', responseLang:'ru'});
  }
  const state = active;
  if (state.busy) return;
  state.busy = true;
  try {
    const result = await state.client.translateVideo({
      videoData: {url:request.url, videoId:request.videoId, host:request.host, duration:request.duration},
      requestLang:request.language || 'auto', responseLang:request.target || 'ru',
      extraOpts: {useLivelyVoice:request.lively === true, firstRequest:state.first, forceSourceLang:request.language !== 'auto'},
      shouldSendFailedAudio:false,
    });
    if (!current(state)) return;
    state.first = false;
    state.translationId = result.translationId || null;
    if (result.status === 6 && !state.translationId) throw new Error('source-unavailable');
    emit(state, {ok:true, ...result, sourceAlreadyUploaded:state.uploaded});
  } catch (error) { fail(state, error, 'translate'); }
  finally { state.busy = false; }
};
globalThis.__votUploadSource = async request => {
  const state = active;
  if (!state || state.request.job !== request.job || state.busy || !state.translationId) return;
  if (!Number.isInteger(request.parts) || request.parts < 1 || request.parts > 16 || state.uploaded) {
    emit(state, {ok:false,error:'source_unavailable',stage:'upload'}); return;
  }
  state.busy = true;
  const fileId = `voicedub:${state.request.videoId}:${state.request.job}`;
  try {
    for (let index = 0; index < request.parts; index++) {
      if (!current(state)) return;
      const encoded = VotNative.sourcePart(request.job, index);
      const data = from64(encoded);
      if (!data.length || data.length > CHUNK_BYTES || (index < request.parts - 1 && data.length !== CHUNK_BYTES)) throw new Error('source-unavailable');
      const result = await state.client.provider.requestVtransAudio(state.request.url, state.translationId,
        {audioFile:data, chunkId:index}, {audioPartsLength:request.parts, fileId, version:1});
      if (!current(state)) return;
      // Match upstream: a decoded HTTP 200 is transport completion. Polling confirms acceptance.
      if (!result || !Number.isInteger(result.status)) throw new Error('upload-rejected');
      emit(state, {ok:true, uploading:true, uploadedParts:index + 1, totalParts:request.parts});
    }
    state.uploaded = true;
    emit(state, {ok:true, uploaded:true});
  } catch (error) { fail(state, error, 'upload'); }
  finally { state.busy = false; }
};
VotNative.ready();
