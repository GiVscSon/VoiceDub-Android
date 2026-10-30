'use strict';
// Run: node --test tests/browser-probe.test.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/browser_probe.js'), 'utf8');
function setup(href, elements) {
  const intervals = [];
  const root = { querySelectorAll: type => type === 'video' ? elements.videos : elements.children || [] };
  const body = { appendChild: () => {} };
  const document = {
    ...root,
    visibilityState: 'visible', body,
    createElement: () => ({id:'',textContent:'',style:{},remove(){}})
  };
  const url = new URL(href);
  const window = {
    location: { href, hostname: url.hostname },
    setTimeout(fn) {fn(); return 1;},
    setInterval(fn) {intervals.push(fn); return 1;}
  };
  const context = {
    window,document,location:window.location,innerWidth:1000,innerHeight:700,
    MutationObserver: class {observe(){}}, URL,Set,WeakSet, Number, Math
  };
  vm.runInNewContext(script,context);
  return { probe:window.__voicedubProbe, intervals };
}
function video(w=640,h=360) {
  return {
    isConnected:true, paused:false, ended:false, readyState:4, currentTime:15.23,
    duration:123.4, currentSrc:'blob:https://youtube.com/a-b', src:'',
    ownerDocument:{defaultView:{location:{href:'https://www.youtube.com/watch?v=abc123',hostname:'www.youtube.com'}}},
    getBoundingClientRect(){return {left:10,top:20,right:10+w,bottom:20+h}},
    querySelector(){return null}
  };
}
test('detects player and stable YouTube ID, even if source is blob',()=>{
 const elements={videos:[video()]}; const {probe}=setup('https://www.youtube.com/watch?v=abc123',elements);
 const s=probe.snapshot();
 assert.equal(s.found,true); assert.equal(s.platform,'YouTube');
 assert.equal(s.videoId,'youtube:abc123'); assert.equal(s.sourceType,'blob/MSE');
 assert.equal(s.position,15.2);
});
test('selects visible playing video, rather than paused preview',()=>{
 const preview=video();preview.paused=true;
 const playing=video(350,220); playing.ownerDocument.defaultView.location.href='https://www.youtube.com/shorts/xyz987';
 const {probe}=setup('https://www.youtube.com/shorts/xyz987',{videos:[preview,playing]});
 assert.equal(probe.snapshot().videoId,'youtube:xyz987');
});
test('finds a newly inserted player inside an open shadow root',()=>{
 const hiddenVideo=video();
 const shadow={querySelectorAll: s=>s==='video'?[hiddenVideo]:[]};
 const host={shadowRoot:shadow};
 const elements={videos:[],children:[]};
 const {probe,intervals}=setup('https://example.org/article',elements);
 assert.equal(probe.snapshot().found,false);
 elements.children.push(host);
 // Simulate the periodic rescan after a new component is inserted.
 intervals[0]();
 assert.equal(probe.snapshot().found,true);
});
test('stops treating removed videos as active after rescan',()=>{
 const a=video();const els={videos:[a]}; const {probe,intervals}=setup('https://www.youtube.com/watch?v=abc123',els);
 assert.equal(probe.snapshot().found,true); a.isConnected=false;els.videos=[];
 intervals[0](); assert.equal(probe.snapshot().found,false);
});

test('source lookup returns only an unsigned audio URL for the matching YouTube video',()=>{
 const v=video();
 v.ownerDocument.getElementById=()=>({getPlayerResponse:()=>({videoDetails:{videoId:'abc123'},streamingData:{adaptiveFormats:[
  {url:'https://r1.googlevideo.com/expensive',mimeType:'audio/mp4',bitrate:128000},
  {url:'https://r1.googlevideo.com/selected',mimeType:'audio/webm',bitrate:48000},
  {signatureCipher:'cipher',url:'https://r1.googlevideo.com/cipher',mimeType:'audio/webm',bitrate:1000},
  {url:'https://r1.googlevideo.com/video',mimeType:'video/webm',bitrate:1000}
 ]}})});
 const {probe}=setup('https://www.youtube.com/watch?v=abc123',{videos:[v]});
 assert.equal(probe.sourceAudio().url,'https://r1.googlevideo.com/selected');
 assert.equal(probe.sourceAudio().playerKey,probe.snapshot().playerKey);
});

test('stale YouTube metadata, DRM and blob handles never become a direct source',()=>{
 const v=video();
 v.ownerDocument.getElementById=()=>({getPlayerResponse:()=>({videoDetails:{videoId:'different'},streamingData:{adaptiveFormats:[{url:'https://r1.googlevideo.com/stale',mimeType:'audio/mp4'}]}})});
 const {probe}=setup('https://www.youtube.com/watch?v=abc123',{videos:[v]});
 assert.equal(probe.sourceAudio().url,undefined);
 v.mediaKeys={};
 assert.equal(probe.sourceAudio().drm,true);
 assert.equal(probe.sourceAudio().url,undefined);
});
