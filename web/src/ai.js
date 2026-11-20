import {createClient} from '@supabase/supabase-js';
import {location} from './summary.js';
const $=id=>document.getElementById(id);
let client, current, running=false;
const messages={
  length:'무료 AI는 본문 10,000자·PDF 10쪽까지 지원합니다.',
  personal:'오늘 이 계정의 무료 AI 한도에 도달했습니다.',
  global:'오늘 서비스 전체 무료 AI 한도에 도달했습니다.',
  busy:'이 문서의 AI 요약이 진행 중입니다. 잠시 후 다시 확인해 주세요.',
  unavailable:'AI 요약을 완료하지 못했습니다.',
  provider_busy:'AI 서비스가 혼잡합니다. 한 번 자동 재시도했지만 완료하지 못했습니다. 잠시 후 다시 이용해 주세요.',
  provider_limit:'AI 공급자의 요청 한도에 도달했습니다. 한도가 회복된 뒤 다시 이용해 주세요.',
  provider_timeout:'AI 응답 대기 시간이 초과됐습니다. 잠시 후 다시 이용해 주세요.',
  provider_connection:'AI 서비스에 연결하지 못했습니다. 잠시 후 다시 이용해 주세요.',
  provider_configuration:'운영자의 AI 연결 설정을 확인해야 합니다. 사용자 결제나 API 키 입력은 필요하지 않습니다.',
  verification:'AI 요약의 금액·기한·원문 근거 검증을 통과하지 못해 결과를 표시하지 않았습니다.',
  response_incomplete:'AI 응답이 완성되지 않아 결과를 표시하지 않았습니다.',
  storage:'사용량 확인 또는 요약 저장에 실패했습니다. 잠시 후 다시 이용해 주세요.',
  login:'AI 요약은 로그인이 필요합니다.'
};
// Verified items of the current document only; reset with it.
export const aiItems=()=>current?.items??[];
export function resetAi(doc=null,purpose=null) {
  current=doc?{doc,purpose}:null;
  $('ai-output').replaceChildren(); $('ai-consent').checked=false; $('ai-status').textContent='';
  $('ai-run').disabled=running||!doc;
}
export async function initializeAi() {
  try {
    const response=await fetch('/api/config',{cache:'no-store'});
    if(!response.ok) return;
    const config=await response.json(); if(!config.url || !config.publicKey) return;
    $('ai-provider').textContent=config.provider;
    if(config.provider==='Google Gemini') $('ai-data-note').textContent='Google 무료 등급에서는 입력과 출력이 제품 개선에 쓰일 수 있습니다. 공개 문서로 이용해 주세요.';
    client=createClient(config.url,config.publicKey,{auth:{flowType:'pkce'}});
    client.auth.onAuthStateChange((_event,session)=>{
      $('ai-panel').hidden=false;
      $('ai-delete').hidden=!session;
      if (_event==='SIGNED_OUT') resetAi();
      $('ai-login').hidden=!!session; $('ai-controls').hidden=!session||!config.enabled; $('ai-logout').hidden=!session;
      $('ai-available').textContent=!config.enabled?'AI 요약은 현재 이용할 수 없습니다. 기존 계정은 로그인 후 삭제할 수 있습니다.':session?'문서를 먼저 정리한 뒤 AI 요약을 실행하세요.':'무료 AI 요약은 Google 로그인 후 사용할 수 있습니다. 로그인 후 문서를 선택해 주세요.';
    });
    $('ai-login').onclick=async()=>{
      const {error}=await client.auth.signInWithOAuth({provider:'google',options:{redirectTo:window.location.origin}});
      if(error) $('ai-status').textContent='Google 로그인을 시작하지 못했습니다.';
    };
    $('ai-logout').onclick=async()=>{await client.auth.signOut();resetAi();};
    $('ai-delete').onclick=async()=>{
      if(!window.confirm('ActionDoc 계정과 저장된 AI 요약·이용 기록을 영구 삭제할까요? 되돌릴 수 없습니다. Google 계정은 유지됩니다.')) return;
      $('ai-delete').disabled=true;
      try {
        const {error}=await client.rpc('actiondoc_delete_my_account');
        if(error) throw error;
        resetAi(); await client.auth.signOut({scope:'local'}); window.location.replace('/');
      } catch { $('ai-status').textContent='탈퇴 완료를 확인하지 못했습니다. 다시 로그인해 확인해 주세요.'; }
      finally { $('ai-delete').disabled=false; }
    };
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
      body:JSON.stringify({blocks:doc.blocks.map(({text,page})=>({text,page})),purpose,pages:doc.pages,consent:'public-document-v1'})});
    const data=await response.json();
    if(job!==current)return;
    if(!['complete','cached'].includes(data.status))throw Error(data.status);
    $('ai-output').replaceChildren();
    for(const item of data.items){
      const article=document.createElement('article'), p=document.createElement('p'), details=document.createElement('details'), summary=document.createElement('summary'),quote=document.createElement('blockquote');
      p.textContent=item.statement;summary.textContent='원문 근거 · '+location(doc.blocks[item.index]);quote.textContent=item.quote;
      details.append(summary,quote);article.append(p,details);$('ai-output').append(article);
    }
    job.items=data.items.map(({statement,quote,index})=>({statement,quote,block:doc.blocks[index]}));
    $('ai-status').textContent=data.status==='cached'?'저장된 요약을 다시 표시했습니다. 무료 횟수는 차감되지 않습니다.':'AI 요약 완료 · 금액과 기한은 인용된 근거와 함께 확인해 주세요.';
  } catch(e) {if(job===current)$('ai-status').textContent=(messages[e.message]||messages.unavailable)+' 기본 정리를 확인해 주세요.';}
  finally {running=false;$('ai-run').disabled=!current;}
}
