import {createClient} from '@supabase/supabase-js';
import {location} from './summary.js';
const $=id=>document.getElementById(id);
let client, current, running=false;
const messages={
  length:'무료 AI는 본문 10,000자·PDF 10쪽까지 지원합니다.',
  personal:'오늘 이 계정의 무료 AI 한도에 도달했습니다.',
  global:'오늘 서비스 전체 무료 AI 한도에 도달했습니다.',
  busy:'이 문서의 AI 요약이 진행 중입니다. 잠시 후 다시 확인해 주세요.',
  unavailable:'AI 연결 또는 원문 검증을 완료하지 못했습니다.',
  login:'AI 요약은 로그인이 필요합니다.'
};
export function resetAi(doc=null,purpose=null) {
  current=doc?{doc,purpose}:null;
  $('ai-output').replaceChildren(); $('ai-consent').checked=false; $('ai-status').textContent='';
  $('ai-run').disabled=running||!doc;
}
export async function initializeAi() {
  try {
    const response=await fetch('/api/config',{cache:'no-store'});
    if(!response.ok) return;
    const config=await response.json(); if(!config.enabled) return;
    client=createClient(config.url,config.publicKey,{auth:{flowType:'pkce'}});
    client.auth.onAuthStateChange((_event,session)=>{
      $('ai-login').hidden=!!session; $('ai-controls').hidden=!session; $('ai-logout').hidden=!session;
      $('ai-available').textContent=session?'문서를 먼저 정리한 뒤 AI 요약을 실행하세요.':'무료 AI 요약은 GitHub 로그인 후 사용할 수 있습니다. 로그인 후 문서를 선택해 주세요.';
    });
    $('ai-login').onclick=async()=>{
      const {error}=await client.auth.signInWithOAuth({provider:'github',options:{redirectTo:window.location.origin}});
      if(error) $('ai-status').textContent='GitHub 로그인을 시작하지 못했습니다.';
    };
    $('ai-logout').onclick=async()=>{await client.auth.signOut();resetAi();};
    $('ai-run').onclick=run;
  } catch { /* Local basic summary remains available. */ }
}
async function run() {
  if(!current||running)return;
  const job=current, {doc,purpose}=job;
  if(doc.pages>10||doc.characters>10000){$('ai-status').textContent=messages.length+' 기본 정리를 확인해 주세요.';return;}
  if(!$('ai-consent').checked){$('ai-status').textContent='본문 전송과 요약 저장에 동의해 주세요.';return;}
  running=true;$('ai-run').disabled=true;
  $('ai-status').textContent='AI가 요약하고 원문을 대조하고 있습니다…';
  try {
    const {data:{session}}=await client.auth.getSession();
    if(!session) throw Error('login');
    const response=await fetch('/api/summary',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+session.access_token},
      body:JSON.stringify({blocks:doc.blocks.map(({text,page})=>({text,page})),purpose,pages:doc.pages,consent:true})});
    const data=await response.json();
    if(job!==current)return;
    if(!['complete','cached'].includes(data.status))throw Error(data.status);
    $('ai-output').replaceChildren();
    for(const item of data.items){
      const article=document.createElement('article'), p=document.createElement('p'), details=document.createElement('details'), summary=document.createElement('summary'),quote=document.createElement('blockquote');
      p.textContent=item.statement;summary.textContent='원문 근거 · '+location(doc.blocks[item.index]);quote.textContent=item.quote;
      details.append(summary,quote);article.append(p,details);$('ai-output').append(article);
    }
    $('ai-status').textContent=data.status==='cached'?'저장된 요약을 다시 표시했습니다. 무료 횟수는 차감되지 않습니다.':'AI 요약 완료 · 금액과 기한은 인용된 근거와 함께 확인해 주세요.';
  } catch(e) {if(job===current)$('ai-status').textContent=(messages[e.message]||messages.unavailable)+' 기본 정리를 확인해 주세요.';}
  finally {running=false;$('ai-run').disabled=!current;}
}
