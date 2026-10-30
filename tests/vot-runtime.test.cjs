const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { webcrypto, createHash } = require('node:crypto');
const bundle = fs.readFileSync('app/src/main/assets/vot_engine.js', 'utf8');
const request = job => ({job, url:'https://youtu.be/abc_def-123', videoId:'abc_def-123', host:'youtube', language:'auto', duration:100, lively:false});
const prototypes = import('../vot-runtime/node_modules/@vot.js/shared/dist/protos/yandex.js');
function runtime(respond, source = () => '') {
  let ready = false, context;
  const requests = [], results = [];
  context = vm.createContext({
    window:{crypto:webcrypto}, crypto:webcrypto, Blob, Headers, Response, TextEncoder, TextDecoder,
    URL, Uint8Array, ArrayBuffer, console, setTimeout, clearTimeout,
    btoa:s => Buffer.from(s, 'binary').toString('base64'), atob:s => Buffer.from(s, 'base64').toString('binary'),
    VotNative:{
      ready() { ready = true; }, sourcePart:source,
      request(job, id, method, url, headers, body) {
        const req = {job,id,method,url,headers:JSON.parse(headers),body:Buffer.from(body,'base64')};
        requests.push(req);
        Promise.resolve().then(() => respond(req)).then(response => {
          if (response) context.__votNetworkResult(id, response.status, Buffer.from(response.body || '').toString('base64'));
        });
      },
      result(id, data) { results.push({id,...JSON.parse(data)}); }
    }
  });
  vm.runInContext(bundle,context);
  assert.equal(ready,true);
  return {context,requests,results};
}
const encoded = (type, value) => type.encode(type.fromPartial(value)).finish();
async function sessionResponse(req, proto, translation) {
  if (req.url.endsWith('/session/create')) return {status:200,body:encoded(proto.YandexSessionResponse,{secretKey:'dGVzdC1zZWNyZXQ=',expires:3600})};
  if (req.url.endsWith('/translate')) return {status:200,body:encoded(proto.VideoTranslationResponse,translation)};
  return {status:200,body:encoded(proto.VideoTranslationAudioResponse,{status:1})};
}

test('VOT diagnostics are fixed categories without leaking server bodies', async () => {
  for (const [status,expected] of [[0,'network'],[401,'http_auth'],[403,'http_auth'],[429,'rate_limit'],[404,'http'],[503,'server']]) {
    const r = runtime(() => ({status,body:'secret-token'}));
    await r.context.__votTranslate(request('one'));
    assert.equal(r.requests.length,1);
    assert.equal(r.requests[0].method,'POST');
    assert.equal(r.requests[0].headers.Authorization,undefined);
    const out = r.results.at(-1);
    assert.equal(out.error,expected); assert.equal(out.stage,'session');
    assert.equal(out.httpStatus,status); assert.equal(out.url,undefined);
    assert.equal(JSON.stringify(out).includes('secret-token'),false);
    r.context.__votCancel();
  }
});

test('real protobuf cycle requests audio, sends ordered nonempty chunks and resumes polling', async () => {
  const p = await prototypes;
  let translated = false;
  const bytes = Buffer.alloc(5295308 + 31, 53);
  const sourceReads = [];
  const r = runtime(req => sessionResponse(req,p, translated ? {status:1,translationId:'t1',url:'https://example.org/ru.mp3'} : {status:6,translationId:'t1'}),
    (job, index) => { sourceReads.push({job,index}); return bytes.subarray(index*5295308,(index+1)*5295308).toString('base64'); });
  await r.context.__votTranslate(request('one'));
  assert.equal(r.results.at(-1).status,6);
  const initial = p.VideoTranslationRequest.decode(r.requests.find(x=>x.url.endsWith('/translate')).body);
  assert.equal(initial.language,'auto'); assert.equal(initial.forceSourceLang,false); assert.equal(initial.firstRequest,true);
  await r.context.__votUploadSource({job:'one',parts:2});
  assert.equal(r.results.at(-1).uploaded,true);
  const uploads = r.requests.filter(x=>x.url.endsWith('/audio'));
  assert.equal(uploads.length,2);
  const parts = uploads.map(x=>p.VideoTranslationAudioRequest.decode(x.body));
  assert.ok(uploads.every(x=>x.method==='PUT' && x.headers['Sec-Vtrans-Token']));
  assert.deepEqual(parts.map(x=>x.partialAudioInfo.audioBuffer.chunkId),[0,1]);
  assert.ok(parts.every(x=>x.translationId==='t1' && x.partialAudioInfo.version===1 && x.partialAudioInfo.audioPartsLength===2));
  assert.equal(parts[0].partialAudioInfo.fileId,parts[1].partialAudioInfo.fileId);
  assert.equal(createHash('sha256').update(Buffer.concat(parts.map(x=>Buffer.from(x.partialAudioInfo.audioBuffer.audioFile)))).digest('hex'),createHash('sha256').update(bytes).digest('hex'));
  assert.deepEqual(sourceReads.map(x=>x.index),[0,1]);
  translated = true;
  await r.context.__votTranslate(request('one'));
  assert.equal(r.results.at(-1).url,'https://example.org/ru.mp3');
  const polls = r.requests.filter(x=>x.url.endsWith('/translate'));
  assert.equal(p.VideoTranslationRequest.decode(polls[1].body).firstRequest,false);
  assert.equal(r.requests.filter(x=>x.url.endsWith('/session/create')).length,1);
  r.context.__votCancel();
});

test('status 5 is a partial track and status 7 requires authorization, neither requests source upload', async () => {
  const p = await prototypes;
  for (const status of [5,7]) {
    const r = runtime(req=>sessionResponse(req,p,{status,translationId:'t',url:status===5?'https://example.org/partial.mp3':undefined}));
    await r.context.__votTranslate(request('one'));
    assert.equal(r.results.at(-1).translated,status===5?true:undefined);
    if (status===7) assert.equal(r.results.at(-1).error,'auth_required');
    assert.equal(r.requests.filter(x=>x.method==='PUT').length,0);
    r.context.__votCancel();
  }
});

test('cancellation during upload stops later chunks and rejects late results from an old job', async () => {
  const p = await prototypes;
  let upload, release;
  const waiting = new Promise(resolve=>release=resolve);
  const r = runtime(async req => {
    if (req.url.endsWith('/audio')) { upload = req; await waiting; }
    return sessionResponse(req,p,{status:6,translationId:'old'});
  },()=>Buffer.alloc(5295308,9).toString('base64'));
  await r.context.__votTranslate(request('old'));
  const promise = r.context.__votUploadSource({job:'old',parts:2});
  while (!upload) await new Promise(resolve=>setImmediate(resolve));
  const previousResults = r.results.length;
  r.context.__votCancel(); release(); await promise;
  assert.equal(r.requests.filter(x=>x.url.endsWith('/audio')).length,1);
  assert.equal(r.results.length,previousResults);
  await r.context.__votUploadSource({job:'old',parts:2});
  assert.equal(r.requests.filter(x=>x.url.endsWith('/audio')).length,1);
});

test('empty or truncated source parts fail without sending fake or partial audio', async () => {
  const p = await prototypes;
  for (const source of ['', Buffer.from('too short').toString('base64')]) {
    const r = runtime(req=>sessionResponse(req,p,{status:6,translationId:'t'}),()=>source);
    await r.context.__votTranslate(request('one'));
    await r.context.__votUploadSource({job:'one',parts:2});
    assert.equal(r.requests.filter(x=>x.url.endsWith('/audio')).length,0);
    assert.equal(r.results.at(-1).error,'source_unavailable');
    r.context.__votCancel();
  }
});


test('VOT uses the requested output language instead of a hardcoded Russian target', async () => {
  const p = await prototypes;
  const r = runtime(req=>sessionResponse(req,p,{status:2,translationId:'t'}));
  await r.context.__votTranslate({...request('one'),target:'en'});
  const translated = p.VideoTranslationRequest.decode(r.requests.find(x=>x.url.endsWith('/translate')).body);
  assert.equal(translated.responseLanguage,'en');
  r.context.__votCancel();
});

test('a missing translation ID fails instead of leaving the source upload stuck', async () => {
  const p = await prototypes;
  const r = runtime(req=>sessionResponse(req,p,{status:6}));
  await r.context.__votTranslate(request('one'));
  assert.equal(r.results.at(-1).error,'source_unavailable');
  r.context.__votCancel();
});
