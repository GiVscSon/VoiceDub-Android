const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { webcrypto } = require('node:crypto');

test('bundled VOT initializes and reports denied transport without fabricating audio', async () => {
  let ready = false, output;
  const requests = [];
  let context;
  context = vm.createContext({
    window: {crypto:webcrypto}, crypto:webcrypto, Blob, Headers, Response, TextEncoder, TextDecoder,
    URL, Uint8Array, ArrayBuffer, console, setTimeout, clearTimeout,
    btoa: s => Buffer.from(s, 'binary').toString('base64'),
    atob: s => Buffer.from(s, 'base64').toString('binary'),
    VotNative: {
      ready() { ready = true; },
      request(id, url, headers, body) {
        requests.push({url, headers:JSON.parse(headers), body});
        queueMicrotask(() => context.__votNetworkResult(id, 503, ''));
      },
      result(id, data) { output = {id, ...JSON.parse(data)}; }
    }
  });
  vm.runInContext(fs.readFileSync('app/src/main/assets/vot_engine.js', 'utf8'), context);
  assert.equal(ready, true);
  await context.__votTranslate({job:'one', url:'https://youtu.be/abc_def-123', videoId:'abc_def-123', host:'youtube', language:'en', duration:100, lively:true});
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, 'https://api.browser.yandex.ru/session/create');
  assert.ok(Buffer.from(requests[0].body, 'base64').length > 0);
  assert.equal(requests[0].headers.Authorization, undefined);
  assert.equal(output.id, 'one');
  assert.equal(output.ok, false);
  assert.equal(output.url, undefined);
});
