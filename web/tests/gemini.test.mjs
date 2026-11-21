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
  const items = [{ kind: 'detail', section: '지원 내용', statement: text, indexes: [0] }];
  let calls = 0;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(new URL(url).origin, 'https://generativelanguage.googleapis.com');
    assert.equal(options.headers['x-goog-api-key'], key);
    assert.ok(!(url + options.body).includes(key));
    if (calls === 0) {
      // Block numbers and counts are bounded by the schema itself, not only checked afterwards.
      const schema = JSON.parse(options.body).generationConfig.responseJsonSchema.properties.items;
      assert.equal(schema.maxItems, undefined);
      assert.deepEqual(schema.items.properties.indexes, { type: 'array', items: { type: 'integer', minimum: 0, maximum: 0 }, minItems: 1 });
      assert.deepEqual(schema.items.properties.kind, { type: 'string', enum: ['overview', 'action', 'detail'] });
    }
    const reply = calls++ === 0 ? { items } : { unsupported: [] };
    return Response.json({ candidates: [{ finishReason: 'STOP', content: { parts: [{ text: JSON.stringify(reply) }] } }] });
  });
  assert.deepEqual(await verifiedSummary([{text}], '전체 정리', 'test', Date.now() + 5000), items);
  assert.equal(calls, 2);
});

test('a changed monetary unit is rejected before the second AI call',async t=>{
  const old=process.env.GEMINI_API_KEY;
  process.env.GEMINI_API_KEY='actiondoc-test-only-credential';
  t.after(()=>{if(old===undefined)delete process.env.GEMINI_API_KEY;else process.env.GEMINI_API_KEY=old;});
  const text='지원금은 최대 100만 원입니다.';
  let calls=0;
  t.mock.method(globalThis,'fetch',async()=>{
    calls++;
    return Response.json({candidates:[{finishReason:'STOP',content:{parts:[{text:JSON.stringify({items:[{indexes:[0],statement:'지원금은 최대 100억 원입니다.'}]})}]}}]});
  });
  await assert.rejects(verifiedSummary([{text}],'전체 정리','test',Date.now()+5000),/numbers/);
  assert.equal(calls,1);
});

test('summary and audit share one deadline, and call logs carry timing but no document text', async t => {
  const old = process.env.GEMINI_API_KEY;
  process.env.GEMINI_API_KEY = 'actiondoc-test-only-credential';
  t.after(() => { if (old === undefined) delete process.env.GEMINI_API_KEY; else process.env.GEMINI_API_KEY = old; });
  const text = '지원금은 300원입니다.';
  const logs = [];
  t.mock.method(console, 'info', (...args) => logs.push(args.join(' ')));
  t.mock.method(console, 'error', (...args) => logs.push(args.join(' ')));
  let calls = 0;
  // The draft answers after 150ms; the audit never answers, so only the time left before the deadline can end it.
  t.mock.method(globalThis, 'fetch', (url, { signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason));
    if (calls++ === 0) setTimeout(() => resolve(Response.json({ usageMetadata: { promptTokenCount: 9, candidatesTokenCount: 4 },
      candidates: [{ finishReason: 'STOP', content: { parts: [{ text: JSON.stringify({ items: [{ statement: text, indexes: [0] }] }) }] } }] })), 150);
  }));
  // AbortSignal.timeout does not hold the event loop open; a live server request does, so the test stands in for it.
  const alive = setTimeout(() => {}, 2000); t.after(() => clearTimeout(alive));
  const started = Date.now();
  await assert.rejects(verifiedSummary([{ text }], '전체 정리', 'test', started + 400), /provider_timeout/);
  assert.equal(calls, 2);
  assert.ok(Date.now() - started < 600, 'the audit waited past the shared deadline');
  assert.match(logs.join('\n'), /"stage":"summary".*"prompt":9.*"output":4/);
  assert.match(logs.join('\n'), /timed out.*"stage":"audit"/);
  assert.ok(!logs.join('\n').includes('300원'));
});

test('the audit removes only the items it rejects, and fails only when it rejects all of them', async t => {
  const old = process.env.GEMINI_API_KEY;
  process.env.GEMINI_API_KEY = 'actiondoc-test-only-credential';
  t.after(() => { if (old === undefined) delete process.env.GEMINI_API_KEY; else process.env.GEMINI_API_KEY = old; });
  t.mock.method(console, 'info', () => {});
  const blocks = [{ text: '지원 대상: 만 19세~39세' }, { text: '신청 기간: 2027. 3. 1. ~ 3. 20.' }];
  const items = [{ kind: 'overview', section: '', statement: '만 19~39세가 대상입니다.', indexes: [0] }, { kind: 'action', section: '', statement: '2027년 3월 1일부터 신청합니다.', indexes: [1] }];
  const run = unsupported => {
    let calls = 0;
    const mock = t.mock.method(globalThis, 'fetch', async () => Response.json({ candidates: [{ finishReason: 'STOP', content: { parts: [{ text: JSON.stringify(calls++ === 0 ? { items } : { unsupported }) }] } }] }));
    return verifiedSummary(blocks, '전체 정리', 'test', Date.now() + 5000).finally(() => mock.mock.restore());
  };
  assert.deepEqual(await run([0]), [items[1]]);
  await assert.rejects(run([0, 1]), /audit/);
});
