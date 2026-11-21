import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {PGlite} from '@electric-sql/pglite';
import {checkEvidence} from '../server/verify.js';

test('evidence must cite existing blocks whose text supports every number',()=>{
  const blocks=[{text:'다만 신규 신청자의 지원금은 최대 50만원이다.'}];
  const item={indexes:[0],statement:'신규 신청자의 한도는 50만원이다.'};
  assert.deepEqual(checkEvidence([item],blocks),[{section:'',...item}]);
  for(const changed of [{indexes:[1]},{indexes:[]},{indexes:[0,-1]},{indexes:[0.5]},{statement:'한도는 50억원이다.'},{statement:'지원금은 500만원이다.'}])
    assert.throws(()=>checkEvidence([{...item,...changed}],blocks));
});

test('numbers compare by value and attached unit, whatever their spelling',()=>{
  const check=(sources,statement)=>checkEvidence([{indexes:sources.map((_,i)=>i),statement}],sources.map(text=>({text})));
  for(const [sources,statement] of [
    [['신청기간: 2026. 5. 11.(월) 10:00 ~ 29.(금) 18:00'],'신청은 2026년 5월 11일 10시부터 5월 29일 18시까지입니다.'],
    [['접수: 2026.5.11~2026.5.29'],'2026년 5월 11일부터 29일까지 접수합니다.'],
    [['지원 대상: 만 19세~39세'],'만 19~39세가 대상입니다.'],
    [['교육비 최대 50만 원'],'교육비를 최대 500,000원 지원합니다.'],
    [['모집인원: 60명','신청 마감: 3월 20일'],'60명을 3월 20일까지 모집합니다.']
  ]) assert.equal(check(sources,statement)[0].statement,statement);
  for(const [sources,statement] of [
    [['계약 기간은 3년입니다.'],'계약 기간은 3개월입니다.'],
    [['신청기간: 2026. 5. 11.'],'신청은 2026년 6월 11일부터입니다.'],
    [['모집인원: 60명'],'60명을 3월 20일까지 모집합니다.']
  ]) assert.throws(()=>check(sources,statement),/numbers/);
});

test('unsupported statements are dropped with content-free reasons, and only an empty result fails',t=>{
  const blocks=Array.from({length:10},(_,i)=>({text:`${i+1}번 항목: 지원 대상은 만 19세~39세 청년입니다.`}));
  const logs=[]; t.mock.method(console,'info',(...args)=>logs.push(args.join(' ')));
  const detail=fn=>{try{fn();}catch(e){return [e.message,e.detail];}};
  // One bad statement no longer discards the verified ones.
  const kept=checkEvidence([{indexes:[0,0,1],statement:'만 19~39세 청년이 대상입니다.'},{indexes:[0,99],statement:'대상'},{indexes:[0],statement:'만 20~39세'}],blocks);
  assert.deepEqual(kept,[{section:'',indexes:[0,1],statement:'만 19~39세 청년이 대상입니다.'}]);
  assert.match(logs.join('\n'),/dropped.*"item":1,"reason":"indexes_range".*"stage":"numbers","item":2,"missing":\["N"\]/);
  assert.ok(!logs.join('\n').includes('20'));
  assert.deepEqual(detail(()=>checkEvidence([{indexes:[0],statement:'만 20~39세 청년'}],blocks)),['numbers',{item:0,missing:['N'],dropped:1}]);
  assert.deepEqual(detail(()=>checkEvidence([{indexes:[],statement:'대상'}],blocks)),['evidence',{item:0,reason:'indexes_empty',dropped:1}]);
  // The model decides how many items and how much evidence a document needs.
  assert.equal(checkEvidence(Array(12).fill({section:'대상',indexes:[0,1,2,3,4,5,6,7,8,9],statement:'대상'}),blocks).length,12);
  assert.deepEqual(detail(()=>checkEvidence([],blocks)),['items',{count:0}]);
});

test('one reservation per concurrent document, account cache survives exhausted budgets, quotas reset by day',async()=>{
  const db=new PGlite();
  try{
    await db.exec('create role anon; create role authenticated; create role service_role; create schema auth; create table auth.users(id uuid primary key); create function auth.uid() returns uuid language sql as $$ select current_setting(\'request.jwt.claim.sub\')::uuid $$;');
    await db.exec(await readFile(new URL('../server/schema.sql',import.meta.url),'utf8'));
    const a='00000000-0000-4000-8000-000000000001', b='00000000-0000-4000-8000-000000000002';
    await db.query('insert into auth.users values ($1),($2)',[a,b]);
    const reserve=async(user,key,personal=1,total=2)=>(await db.query('select public.actiondoc_reserve($1,$2,$3,$4) as slot',[user,key,personal,total])).rows[0].slot;
    const slots=await Promise.all([reserve(a,'doc'),reserve(a,'doc')]);
    assert.deepEqual(slots.map(x=>x.status).sort(),['busy','reserved']);
    assert.equal((await reserve(a,'doc2')).status,'personal');
    await db.query("update actiondoc_jobs set result='[]' where owner=$1",[a]);
    assert.equal((await reserve(b,'doc')).status,'reserved');
    assert.equal((await reserve(b,'doc2')).status,'global');
    assert.equal((await reserve(a,'doc')).status,'cached');
    await db.exec("update actiondoc_jobs set started=now()-interval '25 hours'; update actiondoc_daily_usage set day=day-1");
    assert.equal((await reserve(b,'new')).status,'reserved');
    await db.query("select set_config('request.jwt.claim.sub',$1,false)",[a]);
    await db.exec('set role authenticated; select public.actiondoc_delete_my_account(); reset role;');
    assert.deepEqual((await db.query('select distinct owner from actiondoc_jobs')).rows,[{owner:b}]);
    await assert.rejects(reserve(a,'deleted'));
    await db.exec("update actiondoc_jobs set started=now()-interval '8 days' where fingerprint='doc'");
    const cleanup=(await readFile(new URL('../server/retention.sql',import.meta.url),'utf8')).split('$$')[1];
    await db.exec(cleanup);
    assert.deepEqual((await db.query('select fingerprint from actiondoc_jobs')).rows,[{fingerprint:'new'}]);
    await db.exec('set role anon');
    await assert.rejects(db.exec('select public.actiondoc_delete_my_account()'));

  }finally{await db.close();}
});

test('global counter survives account deletion and migration, with no double charge for cache',async()=>{
  const db=new PGlite();
  try {
    await db.exec("create role anon;create role authenticated;create role service_role;create schema auth;create table auth.users(id uuid primary key);create function auth.uid() returns uuid language sql as $$select current_setting('request.jwt.claim.sub')::uuid$$;");
    const schema=await readFile(new URL('../server/schema.sql',import.meta.url),'utf8');
    await db.exec(schema);
    const a='00000000-0000-4000-8000-000000000001',b='00000000-0000-4000-8000-000000000002';
    await db.query('insert into auth.users values($1),($2)',[a,b]);
    const reserve=async(u,f)=>(await db.query('select actiondoc_reserve($1,$2,3,1) as r',[u,f])).rows[0].r.status;
    assert.equal(await reserve(a,'one'),'reserved');
    assert.equal(await reserve(b,'two'),'global');
    const count=async()=>Number((await db.query('select attempts from actiondoc_daily_usage')).rows[0].attempts);
    await db.exec(schema);
    assert.equal(await count(),1);
    await db.query("update actiondoc_jobs set result='[]' where owner=$1",[a]);
    assert.equal(await reserve(a,'one'),'cached');
    assert.equal(await count(),1);
    await db.query("select set_config('request.jwt.claim.sub',$1,false)",[a]);
    await db.exec('set role authenticated;select actiondoc_delete_my_account();reset role;');
    assert.equal((await db.query('select count(*) as n from actiondoc_jobs')).rows[0].n,0);
    await db.exec(schema);
    assert.equal(await reserve(b,'two'),'global');
    assert.equal(await count(),1);
    await db.exec('set role authenticated');
    await assert.rejects(db.exec('update actiondoc_daily_usage set attempts=0'));
    await db.exec('reset role;set role anon');
    await assert.rejects(db.exec('select * from actiondoc_daily_usage'));
    await db.exec('reset role');
    // Legacy migration starts from existing jobs, not from zero.
    await db.exec('truncate actiondoc_daily_usage');
    await db.query("insert into actiondoc_jobs(owner,fingerprint) values($1,'legacy')",[b]);
    await db.exec(schema);
    assert.equal(await reserve(b,'fresh'),'global');
    assert.equal(await count(),1);
    await assert.rejects(db.query('select actiondoc_reserve($1,$2,3,2)',[a,'deleted']));
    assert.equal(await count(),1);
    await db.exec('truncate actiondoc_jobs, actiondoc_daily_usage');
    const slots=await Promise.all([reserve(b,'parallel1'),reserve(b,'parallel2')]);
    assert.deepEqual(slots.sort(),['global','reserved']);
    assert.equal(await count(),1);
    await db.exec("insert into actiondoc_daily_usage values ((now() at time zone 'Asia/Seoul')::date-8,99)");
    const cleanup=(await readFile(new URL('../server/retention.sql',import.meta.url),'utf8')).split('$$')[1];
    await db.exec(cleanup);
    assert.equal((await db.query('select count(*) as n from actiondoc_daily_usage')).rows[0].n,1);
    assert.equal(await count(),1);
  } finally { await db.close(); }
});

test('amount verification preserves magnitude, currency and sign despite spaces',()=>{
  const check=(quote,statement)=>checkEvidence([{indexes:[0],statement}],[{text:quote}]);
  for(const [quote,statement] of [
    ['지원금 100만 원입니다.','지원금 100억 원입니다.'],
    ['지원금 100만 원입니다.','지원금 100원입니다.'],
    ['지원금 100원입니다.','지원금 100만 원입니다.'],
    ['지원금 1억 5천만 원입니다.','지원금 1억 5백만 원입니다.'],
    ['지원금 1.5억 원입니다.','지원금 1.5만 원입니다.'],
    ['지원금 100달러입니다.','지원금 100원입니다.'],
    ['지원금 $100입니다.','지원금 ₩100입니다.'],
    ['정산액 -100원입니다.','정산액 100원입니다.'],
    ['지원금 100만원입니다.','지원금 100만 원이며 수수료는 50원입니다.'],
    ['지원율 50%입니다.','지원율 50원입니다.']
  ]) assert.throws(()=>check(quote,statement),/numbers/);
  for(const [quote,statement] of [
    ['지원금 100만원입니다.','지원금 100만 원입니다.'],
    ['지원금 100만 원입니다.','지원금 100만원입니다.'],
    ['지원금 1억 5천만 원입니다.','지원금 1 억 5 천 만 원입니다.'],
    ['지원금 1,000,000원입니다.','지원금 1000000 원입니다.'],
    ['지원금 1.5억 원입니다.','지원금 1.5억원입니다.'],
    ['지원율 ５０％입니다.','지원율 50%입니다.'],
    ['접수는 2026. 11. 20. 18:00까지입니다.','접수는 2026. 11. 20. 18:00까지입니다.']
  ]) assert.equal(check(quote,statement)[0].statement,statement);
});
