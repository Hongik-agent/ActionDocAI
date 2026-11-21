import {copyControl,aiText} from './copy.js';
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
const KIND_TITLES={overview:'한눈에 보기',action:'해야 할 일'};
// Saving and copying read as one list: the glance and the to-dos first, then the detailed sections.
const exportItems=items=>['overview','action','detail'].flatMap(kind=>items.filter(i=>(i.kind??'detail')===kind).map(i=>({...i,section:KIND_TITLES[kind]??i.section})));
const tooLarge=doc=>doc.pages>10||doc.characters>10000;
// Says what to change, not only that the document is too long.
const limitMessage=doc=>doc.pages>10?`이 PDF는 ${doc.pages}쪽입니다. AI 요약은 10쪽까지 지원하므로 위의 ‘PDF 정리 범위’에서 10쪽 이내로 지정해 주세요.`:
  `본문이 ${doc.characters.toLocaleString()}자로 AI 요약 한도(10,000자)를 넘습니다. 필요한 부분만 붙여넣거나 PDF 정리 범위를 줄여 주세요.`;
// Verified items of the current document only; reset with it.
export const aiItems=()=>exportItems(current?.items??[]);
export function resetAi(doc=null,purpose=null) {
  current=doc?{doc,purpose}:null;;
  $('ai-output').replaceChildren(); $('ai-consent').checked=false; $('ai-status').textContent='';
}
// One block per section the model chose, in the model's order; shared by real results and the sample example.
export function aiNodes(items) {
  const groups=new Map();
  for(const item of items){
    if(!groups.has(item.section)){const group=document.createElement('section');if(item.section)group.append(Object.assign(document.createElement('h3'),{textContent:item.section}));groups.set(item.section,group);}
    const article=document.createElement('article'), details=document.createElement('details');
    details.append(Object.assign(document.createElement('summary'),{textContent:'원문 근거 · '+item.blocks.map(location).join(', ')}),...item.blocks.map(block=>Object.assign(document.createElement('blockquote'),{textContent:block.text})));
    article.append(Object.assign(document.createElement('p'),{textContent:item.statement}),details);groups.get(item.section).append(article);
  }
  for(const [section,group] of groups){
    const header=document.createElement('div');header.className='section-heading';
    if(group.firstElementChild?.tagName==='H3')header.append(group.firstElementChild);
    header.append(copyControl(()=>aiText(items.filter(i=>i.section===section)),'복사',(section||'AI 요약 항목')+' 복사'));
    group.prepend(header);
  }
  return [...groups.values()];
}
// The glance and the to-dos stay open; the per-section details are folded unless they are all there is.
export function aiView(items) {
  const of=kind=>items.filter(i=>(i.kind??'detail')===kind), make=(tag,props={})=>Object.assign(document.createElement(tag),props), nodes=[];
  const evidence=list=>{
    const blocks=[...new Map(list.flatMap(i=>i.blocks).map(b=>[b.id,b])).values()], d=make('details');
    d.append(make('summary',{textContent:'원문 근거 · '+blocks.map(location).join(', ')}),...blocks.map(b=>make('blockquote',{textContent:b.text})));return d;
  };
  const overview=of('overview'), actions=of('action'), details=of('detail');
  if(overview.length){const s=make('section',{className:'ai-overview'});s.append(make('h3',{textContent:KIND_TITLES.overview}),make('p',{textContent:overview.map(i=>i.statement).join(' ')}),evidence(overview));nodes.push(s);}
  if(actions.length){
    const s=make('section',{className:'ai-actions'}), list=make('ul');
    list.append(...actions.map(i=>{const li=make('li');li.append(make('span',{textContent:i.statement}),evidence([i]));return li;}));
    s.append(make('h3',{textContent:KIND_TITLES.action}),list);nodes.push(s);
  }
  if(details.length){const d=make('details',{className:'ai-details',open:!nodes.length});d.append(make('summary',{textContent:`자세히 보기 · ${details.length}개 항목`}),...aiNodes(details));nodes.push(d);}
  return nodes;
}
export async function aiSignedIn() { return Boolean(client && (await client.auth.getSession()).data.session); }
export function promptLogin() {
  $('ai-status').textContent='AI 요약은 Google 로그인이 필요합니다. 아래 버튼으로 로그인해 주세요.';
  $('ai-panel').scrollIntoView({behavior:'smooth',block:'center'});
}
export function requestAi() { return run(); }
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
      $('ai-login').hidden=!!session; $('ai-login-note').hidden=!!session||!config.enabled; $('ai-controls').hidden=!session||!config.enabled; $('ai-logout').hidden=!session;
      $('ai-available').textContent=!config.enabled?'AI 요약은 현재 이용할 수 없습니다. 기존 계정은 로그인 후 삭제할 수 있습니다.':session?'문서를 선택하고 아래에서 동의한 뒤 ‘AI로 요약하기’를 누르세요.':'무료 AI 요약은 Google 로그인 후 사용할 수 있습니다. 로그인 후 문서를 선택해 주세요.';
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
    $('ai-direct').hidden=!config.enabled;
  } catch { /* Local basic summary remains available. */ }
}
async function run() {
  if(!current||running)return;
  const job=current, {doc,purpose}=job;
  if(tooLarge(doc)){$('ai-status').textContent=limitMessage(doc);return;}
  if(!$('ai-consent').checked){$('ai-status').textContent='본문 전송과 요약 저장에 동의해 주세요.';return;}
  running=true;$('ai-direct').disabled=true;
  $('ai-status').textContent='AI가 요약하고 원문을 대조하고 있습니다…';
  try {
    const {data:{session}}=await client.auth.getSession();
    if(!session) throw Error('login');
    const response=await fetch('/api/summary',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+session.access_token},
      body:JSON.stringify({blocks:doc.blocks.map(({text,page})=>({text,page:page - ((doc.startPage ?? 1) - 1)})),purpose,pages:doc.pages,consent:'public-document-v1'})});
    const data=await response.json();
    if(job!==current)return;
    if(!['complete','cached'].includes(data.status))throw Error(data.status);
    job.items=data.items.map(({kind,section,statement,indexes})=>({kind,section,statement,blocks:indexes.map(i=>doc.blocks[i])}));
    $('ai-output').replaceChildren(copyControl(()=>aiText(exportItems(job.items)),'AI 전체 복사','AI 상세 요약 전체 복사'),...aiView(job.items));
    $('ai-status').textContent=data.status==='cached'?'저장된 요약을 다시 표시했습니다. 무료 횟수는 차감되지 않습니다.':'AI 요약 완료 · 금액과 기한은 인용된 근거와 함께 확인해 주세요.';
  } catch(e) {if(job===current)$('ai-status').textContent=(messages[e.message]||messages.unavailable)+' 기본 정리를 확인해 주세요.';}
  finally {running=false;$('ai-direct').disabled=false;}
}
