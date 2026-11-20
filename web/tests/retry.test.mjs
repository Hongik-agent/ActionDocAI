import test from 'node:test';
import assert from 'node:assert/strict';
import {askGemini} from '../server/gemini.js';
import {summaryFailure} from '../server/errors.js';
import summary from '../api/summary.js';
const reply=value=>Response.json({candidates:[{finishReason:'STOP',content:{parts:[{text:JSON.stringify(value)}]}}]});

test('transient Gemini failures retry once, while rate limits and configuration do not retry',async t=>{
  for(const status of [500,502,503,504]) {
    let calls=0;
    const mock=t.mock.method(globalThis,'fetch',async()=>++calls===1?new Response('',{status}):reply({ok:true}));
    assert.deepEqual(await askGemini('test','',{}, {},10),{ok:true});
    assert.equal(calls,2);mock.mock.restore();
  }
  for(const [status,code] of [[429,'provider_limit'],[400,'provider_configuration'],[401,'provider_configuration'],[402,'provider_configuration'],[403,'provider_configuration'],[404,'provider_configuration'],[503,'provider_busy']]) {
    let calls=0;
    const mock=t.mock.method(globalThis,'fetch',async()=>{calls++;return new Response('sensitive provider error',{status});});
    await assert.rejects(askGemini('test','',{}, {},10),new RegExp(code));
    assert.equal(calls,status===503?2:1);mock.mock.restore();
  }
});

test('network, incomplete output and verification errors are distinct and never leak raw text',async t=>{
  let calls=0;
  const mock=t.mock.method(globalThis,'fetch',async()=>{calls++;throw new TypeError('secret document');});
  await assert.rejects(askGemini('test','',{}, {},10),/provider_connection/);
  assert.equal(calls,1);mock.mock.restore();
  for(const code of ['numbers','audit','evidence','items'])assert.equal(summaryFailure(Error(code)),'verification');
  assert.equal(summaryFailure(Error('incomplete')),'response_incomplete');
  assert.equal(summaryFailure(Error('quota')),'storage');
  assert.equal(summaryFailure(Error('secret document')),'unavailable');
});

test('retry shares the original deadline instead of creating another full timeout',async t=>{
  const old=AbortSignal.timeout;
  let calls=0,deadlines=0;
  t.mock.method(AbortSignal,'timeout',()=>{deadlines++;return old.call(AbortSignal,20);});
  t.mock.method(globalThis,'fetch',async()=>{calls++;return new Response('',{status:503});});
  await assert.rejects(askGemini('test','',{}, {},10),/provider_timeout/);
  assert.equal(calls,1);assert.equal(deadlines,1);
});

test('API retries inside one reservation and returns safe provider error codes',async t=>{
  // Supabase constructs its unused realtime client even for HTTP-only API tests on Node 20.
  const socket=globalThis.WebSocket;
  if(!socket)globalThis.WebSocket=class {};
  t.after(()=>{if(!socket)delete globalThis.WebSocket;});
  const keys=['SUPABASE_URL','SUPABASE_SERVICE_ROLE_KEY','GEMINI_API_KEY'];
  const old=Object.fromEntries(keys.map(k=>[k,process.env[k]]));
  process.env.SUPABASE_URL='https://test.supabase.co';process.env.SUPABASE_SERVICE_ROLE_KEY='test-service-key';process.env.GEMINI_API_KEY='test-gemini-key';
  t.after(()=>{for(const k of keys){if(old[k]===undefined)delete process.env[k];else process.env[k]=old[k];}});
  for(const succeeds of [true,false]) {
    let reserves=0,generations=0,saves=0;
    const text='지원금은 300원입니다.';
    const mock=t.mock.method(globalThis,'fetch',async(url,options)=>{
      const target=new URL(url instanceof Request?url.url:url);
      const path=target.pathname;
      if(path==='/auth/v1/user')return Response.json({id:'00000000-0000-4000-8000-000000000001'});
      if(path==='/rest/v1/rpc/actiondoc_reserve'){reserves++;return Response.json({status:'reserved',job:'job-test'});}
      if(path==='/rest/v1/actiondoc_jobs'){saves++;return new Response(null,{status:204});}
      assert.equal(target.origin,'https://generativelanguage.googleapis.com');
      generations++;
      if(!succeeds || generations===1)return new Response('private detail',{status:503});
      return reply(generations===2?{items:[{statement:text,quote:text,index:0}]}:{supported:true});
    });
    let body,status=200;
    await summary({method:'POST',headers:{authorization:'Bearer test-user-token'},body:{blocks:[{text,page:0}],purpose:'GENERAL',pages:0,consent:'public-document-v1'}},
      {setHeader(){},status(n){status=n;return this;},json(value){body=value;return this;}});
    assert.equal(reserves,1);assert.equal(generations,succeeds?3:2);assert.equal(saves,succeeds?1:0);
    assert.equal(body.status,succeeds?'complete':'provider_busy');assert.equal(status,succeeds?200:503);
    assert.ok(!JSON.stringify(body).includes('private detail'));mock.mock.restore();
  }
});
