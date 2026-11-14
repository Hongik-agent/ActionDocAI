import test from 'node:test';
import assert from 'node:assert/strict';
import config from '../api/config.js';
import {verifiedSummary} from '../server/verify.js';

test('Gemini key stays in the provider header across summary and evidence audit', async t => {
  const old = process.env.GEMINI_API_KEY;
  const key = 'actiondoc-test-only-credential';
  process.env.GEMINI_API_KEY = key;
  t.after(() => { if (old === undefined) delete process.env.GEMINI_API_KEY; else process.env.GEMINI_API_KEY = old; });
  config({}, { setHeader() {}, json(body) {
    assert.equal(body.provider, 'Google Gemini');
    assert.ok(!JSON.stringify(body).includes(key));
  }});
  const text = '지원금은 300원입니다.';
  const items = [{ statement: text, quote: text, index: 0 }];
  let calls = 0;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(new URL(url).origin, 'https://generativelanguage.googleapis.com');
    assert.equal(options.headers['x-goog-api-key'], key);
    assert.ok(!(url + options.body).includes(key));
    const reply = calls++ === 0 ? { items } : { supported: true };
    return Response.json({ candidates: [{ finishReason: 'STOP', content: { parts: [{ text: JSON.stringify(reply) }] } }] });
  });
  assert.deepEqual(await verifiedSummary([{text}], '전체 정리', 'test'), items);
  assert.equal(calls, 2);
});
