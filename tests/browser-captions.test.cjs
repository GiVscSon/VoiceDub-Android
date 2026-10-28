'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/browser_captions.js'),'utf8');
function harness() {
  const timers=[];
  let active;
  let el;
  const document={body:{appendChild(node){el=node;node.isConnected=true}},createElement(tag){return {tagName:tag,style:{},isConnected:false,setAttribute(name,val){this[name]=val},textContent:''}}};
  const window={__voicedubProbe:{currentVideo:()=>active},setInterval(fn){timers.push(fn)}};
  vm.runInNewContext(source,{window,document,Number,String,Object});
  return {captions:window.__voicedubCaptions, setVideo(v){active=v},getElement:()=>el,tick(){timers.forEach(f=>f())}};
}
function media(){return {paused:false,ended:false,seeking:false,currentTime:12.4,src:'https://example.org/movie.mp4',currentSrc:'https://example.org/movie.mp4',isConnected:true}}
const cue=(seq=1,text='Переведённый текст')=>({original:'Example',translated:text,session:'100',sequence:seq});
test('rejects captions when video is absent, paused or result invalid',()=>{
 const h=harness(),v=media();
 assert.equal(h.captions.push(cue()).reason,'no-playing-video');
 h.setVideo(v);v.paused=true;
 assert.equal(h.captions.push(cue()).reason,'no-playing-video');
 v.paused=false;
 assert.equal(h.captions.push({translated:'',session:'100',sequence:1}).reason,'invalid-caption');
 assert.equal(h.captions.status().active,false);
});
test('shows live translated text safely as textContent, never HTML',()=>{
 const h=harness();h.setVideo(media());
 const text='<img src=x onerror=alert(1)> & текст';
 assert.equal(h.captions.push(cue(1,text)).ok,true);
 assert.equal(h.getElement().textContent,text);
 assert.equal(h.getElement().style.display,'');
 assert.equal(h.captions.status().at,12.4);
});
test('deduplicates sequence and permits next caption in same session',()=>{
 const h=harness();h.setVideo(media());
 assert.equal(h.captions.push(cue(3)).ok,true);
 assert.equal(h.captions.push(cue(2)).reason,'stale-caption');
 assert.equal(h.captions.push(cue(3)).reason,'stale-caption');
 assert.equal(h.captions.push(cue(4,'Следующая')).ok,true);
 assert.equal(h.getElement().textContent,'Следующая');
});
test('expires text and clears on pause, seek, swap and changed source',()=>{
 const h=harness();const v=media();h.setVideo(v);
 h.captions.push(cue());v.currentTime=18.5;h.tick();
 assert.equal(h.captions.status().active,false);
 h.captions.push(cue(2));v.paused=true;h.tick();
 assert.equal(h.captions.status().active,false);
 v.paused=false;h.captions.push(cue(3));v.seeking=true;h.tick();
 assert.equal(h.captions.status().active,false);
 v.seeking=false;h.captions.push(cue(4));v.src='https://example.org/new.mp4';h.tick();
 assert.equal(h.captions.status().active,false);
 v.src='https://example.org/movie.mp4';h.captions.push(cue(5));h.setVideo(media());h.tick();
 assert.equal(h.captions.status().active,false);
});
