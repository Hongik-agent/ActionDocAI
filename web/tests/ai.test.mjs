import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {PGlite} from '@electric-sql/pglite';
import {checkEvidence} from '../server/verify.js';

test('evidence must exist in the cited block and retain numbers and units',()=>{
  const blocks=[{text:'다만 신규 신청자의 지원금은 최대 50만원이다.'}];
  const item={index:0,quote:blocks[0].text,statement:'신규 신청자의 한도는 50만원이다.'};
  assert.deepEqual(checkEvidence([item],blocks),[item]);
  for(const changed of [{index:1},{quote:'다른 사업'},{statement:'한도는 50억원이다.'},{statement:'지원금은 500만원이다.'}])
    assert.throws(()=>checkEvidence([{...item,...changed}],blocks));
});

test('one reservation per concurrent document, account cache survives exhausted budgets, quotas reset by day',async()=>{
  const db=new PGlite();
  try{
    await db.exec('create role anon; create role authenticated; create role service_role;');
    await db.exec(await readFile(new URL('../server/schema.sql',import.meta.url),'utf8'));
    const a='00000000-0000-4000-8000-000000000001', b='00000000-0000-4000-8000-000000000002';
    const reserve=async(user,key,personal=1,total=2)=>(await db.query('select public.actiondoc_reserve($1,$2,$3,$4) as slot',[user,key,personal,total])).rows[0].slot;
    const slots=await Promise.all([reserve(a,'doc'),reserve(a,'doc')]);
    assert.deepEqual(slots.map(x=>x.status).sort(),['busy','reserved']);
    assert.equal((await reserve(a,'doc2')).status,'personal');
    await db.query("update actiondoc_jobs set result='[]' where owner=$1",[a]);
    assert.equal((await reserve(b,'doc')).status,'reserved');
    assert.equal((await reserve(b,'doc2')).status,'global');
    assert.equal((await reserve(a,'doc')).status,'cached');
    await db.exec("update actiondoc_jobs set started=now()-interval '25 hours'");
    assert.equal((await reserve(b,'new')).status,'reserved');
  }finally{await db.close();}
});
