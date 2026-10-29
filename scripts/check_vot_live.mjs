// Optional anonymous probe against Yandex using a public demonstration video.
// No account token; never print signed URLs, session keys, request/response bodies.
import VOTClient from '../vot-runtime/node_modules/@vot.js/core/dist/client.js';
const report = {time:new Date().toISOString(), anonymous:true, http:[]};
const client = new VOTClient({fetchFn:async (url, options={})=>{
 const res = await fetch(url,{...options,signal:AbortSignal.timeout(20000),redirect:'error'});
 if (res.status !== 200) {
   report.errorContentType = res.headers.get('content-type');
   const text = (await res.clone().text()).slice(0, 512);
   // Error-only public probe. Retain plain short diagnostic text, never URL-bearing bodies.
   if (!/https?:|token|secret|authorization/i.test(text)) report.errorSummary = text;
 }
 report.http.push({stage:url.endsWith('/session/create')?'session':'translate',status:res.status});
 return res;
}});
try {
 const r=await client.translateVideo({videoData:{url:'https://youtu.be/aqz-KE-bpKQ',videoId:'aqz-KE-bpKQ',host:'youtube',duration:634},requestLang:'auto',responseLang:'ru',shouldSendFailedAudio:false});
 report.result={status:r.status,translated:r.translated,hasAudioUrl:!!r.url};
} catch (e) {
 report.error=e?.data?.status===7?'auth-required':(report.http.length?'service-rejected':'transport-failed');
 report.transportCode=e?.cause?.code || null;
}
console.log(JSON.stringify(report,null,2));
