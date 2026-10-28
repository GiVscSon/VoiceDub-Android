'use strict';
// Node-only deterministic event model; a WebView/device test is still needed.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/browser_dubbing.js'),'utf8');
class Media {
  constructor() { this.listeners=new Map();this.paused=true;this.ended=false;this.seeking=false;this.readyState=4;this.currentTime=0;this.duration=180;this.playbackRate=1;this.isConnected=true;this.currentSrc='https://example.org/vid.mp4';this.src='';this.playAttempts=0;this.playBlocked=false;this.attributes={}; }
  addEventListener(type,fn){const a=this.listeners.get(type)||[];a.push(fn);this.listeners.set(type,a)}
  removeEventListener(type,fn){const a=this.listeners.get(type)||[];this.listeners.set(type,a.filter(f=>f!==fn))}
  emit(type){for(const fn of this.listeners.get(type)||[]) fn()}
  pause(){this.paused=true}
  play(){this.playAttempts++;if(this.playBlocked)return Promise.reject(new Error('gesture'));this.paused=false;return Promise.resolve()}
  load(){}
  removeAttribute(name){delete this.attributes[name];if(name==='src')this.src=''}
}
function make() {
  const video=new Media();video.paused=false;
  const audio=[];
  const intervals=new Map();let seq=0;
  let inUse=video;
  let snapshotId='site:example';
  let button;
  const document={body:{appendChild(el){button=el}},createElement(){return {style:{},listeners:new Map(),addEventListener(type,fn){this.listeners.set(type,fn)},click(isTrusted=true){this.listeners.get('click')?.({isTrusted})},remove(){}}}};
  const window={__voicedubProbe:{currentVideo:()=>inUse,snapshot:()=>({videoId:snapshotId})},
    setInterval(fn){const id=++seq;intervals.set(id,fn);return id},clearInterval(id){intervals.delete(id)}};
  class TestAudio extends Media {constructor(){super();audio.push(this);this.currentSrc='';}}
  const context={window,document,Audio:TestAudio, URL,Number,Math,Object,String};
  vm.runInNewContext(source,context);
  return {control:window.__voicedubDubbing,video,audio,intervals,
    tick(){for(const fn of [...intervals.values()])fn()},
    click(isTrusted=true){button.click(isTrusted)},
    setActive(v){inUse=v},
    setId(v){snapshotId=v},
    button:()=>button};
}

test('rejects unsafe or malformed media links; and missing video',()=>{
 const a=make();
 assert.equal(a.control.attach({url:'javascript:alert(1)'}).error,'https-required');
 assert.equal(a.control.attach({url:'not an url'}).error,'invalid-audio-url');
 assert.equal(a.control.attach({url:'http://cdn.test/dub.mp3'}).error,'https-required');
 a.setActive(null);
 assert.equal(a.control.attach({url:'https://cdn.test/dub.mp3'}).error,'no-active-video');
 assert.equal(a.audio.length,0);
});
test('requires explicit gesture and attaches without starting remote playback',async()=>{
 const a=make();a.video.currentTime=12.4;
 const result=a.control.attach({url:'https://audio.test/russian.mp3',videoId:'site:example'});
 assert.equal(result.ok,true); assert.equal(a.audio.length,1);
 assert.equal(a.audio[0].playAttempts,0);
 assert.equal(a.audio[0].src,'');
 assert.equal(a.control.status().needsGesture,true);
 a.click(); await Promise.resolve();
 assert.equal(a.audio[0].playAttempts,1);
 assert.equal(a.audio[0].paused,false);
 assert.equal(a.audio[0].currentTime,12.4);
 assert.equal(a.control.status().state,'playing');
});
test('pause, resume, seek and speed change follow original video',async()=>{
 const a=make();a.video.currentTime=10;
 a.control.attach({url:'https://audio.test/dub.mp3'});a.click();await Promise.resolve();
 const audio=a.audio[0];
 a.video.paused=true;a.video.emit('pause');assert.equal(audio.paused,true);
 a.video.currentTime=43;a.video.seeking=true;a.video.emit('seeking');assert.equal(audio.paused,true);
 a.video.seeking=false;a.video.emit('seeked');assert.equal(audio.paused,true);
 a.video.paused=false;a.video.emit('playing');await Promise.resolve();
 assert.equal(audio.paused,false);assert.equal(audio.currentTime,43);
 a.video.playbackRate=1.5;a.video.emit('ratechange');
 assert.equal(audio.playbackRate,1.5);
 a.video.ended=true;a.video.emit('ended'); assert.equal(audio.paused,true);
});
test('soft drift changes tempo, large drift hard-seeks',async()=>{
 const a=make();a.video.currentTime=55;
 a.control.attach({url:'https://audio.test/dub.mp3'});a.click();await Promise.resolve();
 const audio=a.audio[0];
 audio.currentTime=55.3;a.tick();assert.equal(audio.playbackRate,0.95);
 audio.currentTime=54.75;a.tick();assert.equal(audio.playbackRate,1.05);
 audio.currentTime=55;a.tick();assert.equal(audio.playbackRate,1);
 audio.currentTime=51;a.tick();assert.equal(audio.currentTime,55);
});
test('video source change detaches stale audio and clears listeners/timer',async()=>{
 const a=make();a.control.attach({url:'https://audio.test/dub.mp3'});a.click();await Promise.resolve();
 const audio=a.audio[0];
 a.video.currentSrc='https://example.org/next.mp4'; a.tick();
 assert.equal(a.control.status().attached,false);
 assert.equal(audio.paused,true); assert.equal(a.intervals.size,0);
 assert.equal(a.video.listeners.get('timeupdate').length,0);
});
test('new player replacement detaches even when URL and page are same',async()=>{
 const a=make();a.control.attach({url:'https://audio.test/dub.mp3'});a.click();await Promise.resolve();
 const replacement=new Media(); replacement.paused=false;
 a.setActive(replacement);a.tick();
 assert.equal(a.control.status().attached,false);
});
test('video id mismatch refuses attachment; reattachment cleans prior audio',()=>{
 const a=make();
 assert.equal(a.control.attach({url:'https://audio.test/dub.mp3',videoId:'other'}).error,'video-changed');
 a.control.attach({url:'https://audio.test/one.mp3'});
 const old=a.audio[0];
 a.control.attach({url:'https://audio.test/two.mp3'});
 assert.equal(old.paused,true);assert.equal(a.audio.length,2);assert.equal(a.intervals.size,1);
});
test('end of dubbed track does not repeatedly seek or crash',async()=>{
 const a=make();a.control.attach({url:'https://audio.test/dub.mp3'});a.click();await Promise.resolve();
 a.video.currentTime=185;a.tick();
 assert.equal(a.control.status().state,'track-ended');
 assert.equal(a.audio[0].paused,true);
});
test('playback rejection surfaces gesture required and does not claim playing',async()=>{
 const a=make();a.control.attach({url:'https://audio.test/dub.mp3'});
 a.audio[0].playBlocked=true;a.click();await new Promise(resolve=>setImmediate(resolve));
 assert.equal(a.control.status().state,'gesture-required');
 assert.equal(a.control.status().needsGesture,true);
});

test('programmatic fake click cannot load audio or activate translation',()=>{
 const a=make();a.control.attach({url:'https://audio.test/dub.mp3'});
 a.click(false);
 assert.equal(a.audio[0].src,'');
 assert.equal(a.audio[0].playAttempts,0);
 assert.equal(a.control.status().state,'armed');
});
