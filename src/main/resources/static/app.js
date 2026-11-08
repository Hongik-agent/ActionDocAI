'use strict';
const $ = id => document.getElementById(id);
const labels = {change:'변경 조건',fee:'요금·금액',date:'주요 날짜',action:'필요한 조치',impact:'미처리 영향'};
const state = {csrf:'',aiConfigured:false,input:'file',file:null,doc:null,analysis:null,tasks:[],filter:'all',taskFilter:'ALL',selected:null,busy:false};
const sample = `통신 요금제 변경 및 할인 신청 안내
안녕하세요. 예시통신 고객센터입니다.

1. 변경되는 월 이용요금
2026년 12월 1일부터 기본 월 이용요금이 기존 33,000원에서 36,300원으로 변경됩니다. 두 요금 모두 부가세 포함 금액입니다.
이번 변경은 라이트 5G 요금제 가입자에게만 적용됩니다. 다른 요금제 가입자는 적용 대상이 아닙니다.

2. 할인 신청
2026년 11월 25일까지 고객센터에 장기고객 할인 신청서를 제출해 주세요.
장기고객 할인은 가입 기간 24개월 이상인 고객에게 월 3,300원이 적용됩니다.
할인 신청서를 제출하지 않으면 장기고객 할인이 적용되지 않아 기본 월 이용요금이 청구됩니다.

3. 계약 유지
서비스는 별도 동의 없이 유지됩니다. 해지를 원하시면 기존 계약의 약정 및 위약금 조건을 고객센터에서 확인해 주세요.
문의: 예시통신 고객센터 (테스트용 비식별 안내문)`;
function el(tag,cls,text){const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;}
function sourcePage(page){return page>0?`${page}페이지`:'본문';}
function clear(node){node.replaceChildren();}
function toast(message,error=false){$('toast').textContent=message;$('toast').classList.toggle('error',error);$('toast').hidden=false;clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('toast').hidden=true,4500);}
async function api(path,options={}){
 const headers={'X-CSRF-Token':state.csrf,...options.headers};
 if(options.body && !(options.body instanceof FormData))headers['Content-Type']='application/json';
 const response=await fetch('/api'+path,{...options,headers});
 const data=await response.json().catch(()=>({error:'서버 응답을 읽지 못했습니다.'}));
 if(!response.ok)throw new Error(data.error||'요청을 처리하지 못했습니다.');
 return data;
}
function setInput(type){state.input=type;$('file-input-panel').hidden=type!=='file';$('text-input-panel').hidden=type!=='text';for(const t of ['file','text']){$(t+'-tab').classList.toggle('selected',type===t);$(t+'-tab').setAttribute('aria-selected',type===t);} }
function chooseFile(file){if(!file)return;if(file.size>50*1024*1024){toast('50MB 이하 파일을 선택해 주세요.',true);return;}if(!/\.(pdf|hwp|html?|txt|md)$/i.test(file.name)){toast('PDF, HWP, HTML, TXT, MD 파일을 선택해 주세요.',true);return;}state.file=file;$('file-name').textContent=file.name;$('file-size').textContent=(file.size/1024).toFixed(1)+' KB · 분석 준비 완료';$('file-preview').hidden=false;$('dropzone').hidden=true;invalidate();}
function invalidate(){state.doc=null;state.analysis=null;$('results').hidden=true;$('input-error').hidden=true;setStep(1);}
function setStep(step){for(let i=1;i<=3;i++)$('step-'+i).classList.toggle('active',i===step);}
function setBusy(busy){state.busy=busy;$('analyze-button').disabled=busy;$('processing').hidden=!busy;document.querySelectorAll('.input-tabs button,#load-sample,#remove-file,#file,#document-text,#context,#summary-purpose,#analysis-mode,#consent').forEach(n=>n.disabled=busy);}
function showView(view){$('view-analyze').hidden=view!=='analyze';$('view-tasks').hidden=view!=='tasks';document.querySelectorAll('[data-view]').forEach(n=>n.classList.toggle('active',n.dataset.view===view));$('breadcrumb').textContent='워크스페이스 / '+(view==='tasks'?'내 작업':'문서 분석');if(view==='tasks')refreshTasks().catch(e=>toast(e.message,true));window.scrollTo({top:0,behavior:'smooth'});}
function updateAnalysisAvailability(){
 const ai=$('analysis-mode').value==='ai';
 $('consent-label').hidden=!ai;
 $('mode-description').textContent=ai?(state.aiConfigured?'AI가 문맥과 조건을 요약하고 원문 근거를 검증합니다. 설정된 API 계정에 사용 비용이 발생합니다.':'API가 연결되지 않았습니다. 개인 API 키 등록은 Mac 앱의 AI 설정에서 사용할 수 있습니다.'):'무료 기본 정리: API 키와 AI 사용료 없이 원문을 발췌합니다. 외부 AI로 전송하지 않습니다.';
 $('analyze-button').disabled=state.busy||(ai&&!state.aiConfigured);
}
async function uploadDocument(form){
 const id=crypto.randomUUID();form.append('progressId',id);let active=true;
 const controller=new AbortController();
 const poll=async()=>{while(active){try{const progress=await api('/documents/progress/'+id,{signal:controller.signal});if(active)$('processing-text').textContent=progress.message;}catch(e){}if(active)await new Promise(resolve=>setTimeout(resolve,750));}};
 void poll();
 try{return await api('/documents',{method:'POST',body:form});}finally{active=false;controller.abort();}
}
async function analyze(){
 if(state.busy)return;
 $('input-error').hidden=true;
 if(state.input==='file'&&!state.file){toast('분석할 파일을 선택해 주세요.',true);return;}
 if(state.input==='text'&&!$('document-text').value.trim()){toast('안내문 내용을 입력해 주세요.',true);return;}
 const mode=$('analysis-mode').value;
 if(mode==='ai'&&!state.aiConfigured){toast('AI 연결 설정이 필요합니다.',true);return;}
 if(mode==='ai'&&!$('consent').checked){toast('문서 전송 및 API 사용 비용에 동의해 주세요.',true);return;}
 setBusy(true);$('results').hidden=true;
 try{
  $('processing-text').textContent=state.input==='file'?'파일에서 본문과 원문 위치를 추출하고 있습니다…':'입력한 본문을 정리하고 있습니다…';
  const form=new FormData();if(state.input==='file')form.append('file',state.file);else form.append('text',$('document-text').value);
  state.doc=await uploadDocument(form);
  $('processing-text').textContent=mode==='ai'?'전체 문서를 분석하고 원문 인용을 검증하고 있습니다…':'요금·날짜·행동 키워드가 포함된 원문을 찾고 있습니다…';
  state.analysis=await api('/analyses',{method:'POST',body:JSON.stringify({documentId:state.doc.id,context:$('context').value,mode,consent:mode==='ai'&&$('consent').checked,purpose:$('summary-purpose').value})});
  state.filter='all';renderResults();setStep(2);$('results').hidden=false;$('results').scrollIntoView({behavior:'smooth',block:'start'});
 }catch(e){$('input-error').textContent=e.message;$('input-error').hidden=false;}
 finally{setBusy(false);updateAnalysisAvailability();}
}
function renderResults(){
 const a=state.analysis,d=state.doc;
 $('result-meta').textContent=`${d.name} · ${d.pages>0?d.pages+'페이지':'쪽 번호 없음'} · ${d.characters.toLocaleString()}자 · ${a.chunks}개 구간 분석 · ${a.mode==='ai'?'AI 문맥 분석':'규칙 기반 미리보기'}`;
 clear($('analysis-notice'));const decisionText={REVIEW_TASKS:'추천된 조치가 있습니다. 적용 대상과 기한을 검토한 뒤 필요한 작업을 등록하세요.',NEEDS_REVIEW:'추가 확인이 필요합니다. 원문과 질문을 확인해 주세요.',NO_ACTION:'별도 조치가 불필요한 것으로 분석했습니다. 본인 적용 여부와 원문을 확인해 주세요.'};$('analysis-notice').append(el('div','notice decision-notice',decisionText[a.decision]||'분석 내용을 확인해 주세요.'));a.warnings.forEach(w=>$('analysis-notice').append(el('div','notice',w)));
 clear($('result-stats'));for(const [cat,label]of Object.entries(labels)){const box=el('div','stat');box.append(el('span','',label),el('strong','',a.findings.filter(f=>f.category===cat).length));$('result-stats').append(box);}
 clear($('filter-tabs'));for(const [cat,label]of Object.entries({all:'전체',...labels})){const b=el('button',cat===state.filter?'selected':'',label);b.addEventListener('click',()=>{state.filter=cat;renderFindings();for(const x of $('filter-tabs').children)x.classList.toggle('selected',x===b);});$('filter-tabs').append(b);}
 clear($('questions'));a.questions.forEach(q=>$('questions').append(el('li','',q)));if(!a.questions.length)$('questions').append(el('li','','추가 질문은 없습니다. 적용 대상과 조건을 직접 확인해 주세요.'));
 $('source-type').textContent=d.type;clear($('source-content'));for(const b of d.blocks){const block=el('div','source-block');block.id='source-'+b.id;block.append(el('small','',`${sourcePage(b.page)} · ${b.id}`),el('span','',b.text));$('source-content').append(block);}
 renderFindings();
}
function focusSource(f){const target=$('source-'+f.blockId);if(!target)return;document.querySelectorAll('.source-block.highlight').forEach(n=>n.classList.remove('highlight'));target.classList.add('highlight');target.scrollIntoView({behavior:'smooth',block:'nearest'});}
function renderFindings(){
 clear($('findings'));
 if(state.filter==='all' && state.analysis.sections?.length){
  for(const section of state.analysis.sections){
   const card=el('article','panel finding-card');card.append(el('h3','',section.title),el('p','',section.body));
   const details=el('details');details.append(el('summary','',`원문 근거 ${section.evidence.length}곳`));
   for(const evidence of section.evidence){const button=el('button','quote-button');button.append(el('span','',evidence.quote),el('small','',sourcePage(evidence.page)));button.addEventListener('click',()=>focusSource(evidence));details.append(button);}
   card.append(details);$('findings').append(card);
  }return;
 }
 const items=state.analysis.findings.filter(f=>state.filter==='all'||state.filter===f.category);
 if(!items.length){const empty=el('div','empty-state');empty.append(el('strong','','확인된 항목이 없습니다'),el('span','','문서에 없거나 분석으로 확인하지 못한 내용입니다.'));$('findings').append(empty);return;}
 for(const f of items){const card=el('article','panel finding-card');const top=el('div','finding-top');top.append(el('span','category-label',labels[f.category]),el('span','verified','✓ 원문 인용 일치'));card.append(top,el('h3','',f.title),el('p','',f.detail));const quote=el('button','quote-button');quote.append(el('span','',f.quote),el('small','',`${sourcePage(f.page)} · ${f.blockId} 원문 확인 ↗`));quote.addEventListener('click',()=>focusSource(f));card.append(quote);if(f.category==='action'){const bottom=el('div','finding-bottom');bottom.append(el('span','',f.date?'기한 '+f.date:'기한 확인 필요'));const added=state.tasks.some(t=>t.analysisId===state.analysis.id&&t.findingId===f.id);const b=el('button','text-button',added?'✓ 등록됨':'+ 작업으로 등록');b.disabled=added;b.addEventListener('click',()=>openTask(f));bottom.append(b);card.append(bottom);}else if(f.date)card.append(el('div','finding-bottom',f.date));$('findings').append(card);}
}
function openTask(f){state.selected=f;$('task-title').value=f.title==='필요한 조치 검토'?'안내문 조치 확인 및 처리':f.title;$('task-description').value=f.detail;$('task-date').value=f.date||'';$('task-evidence').textContent=f.quote+`\n— ${sourcePage(f.page)} · ${f.blockId}`;$('task-approved').checked=false;$('task-error').hidden=true;$('task-dialog').showModal();}
async function saveTask(event){event.preventDefault();if(!$('task-approved').checked)return;$('save-task').disabled=true;$('task-error').hidden=true;try{await api('/tasks',{method:'POST',body:JSON.stringify({analysisId:state.analysis.id,findingId:state.selected.id,title:$('task-title').value,description:$('task-description').value,dueDate:$('task-date').value||null,approved:$('task-approved').checked})});$('task-dialog').close();await refreshTasks();renderFindings();setStep(3);toast('확인한 작업을 등록했습니다. 내 작업에서 확인하세요.');}catch(e){$('task-error').textContent=e.message;$('task-error').hidden=false;}finally{$('save-task').disabled=false;}}
async function refreshTasks(){state.tasks=await api('/tasks');$('task-count').textContent=state.tasks.filter(t=>t.status==='TODO').length;renderTasks();}
function renderTasks(){
 const todo=state.tasks.filter(t=>t.status==='TODO').length;clear($('task-summary'));$('task-summary').append(document.createTextNode('등록된 작업 '),el('strong','',state.tasks.length),document.createTextNode('개 · 할 일 '),el('strong','',todo),document.createTextNode('개 · 완료 '),el('strong','',state.tasks.length-todo),document.createTextNode('개'));
 clear($('tasks'));const tasks=state.tasks.filter(t=>state.taskFilter==='ALL'||t.status===state.taskFilter);
 if(!tasks.length){const empty=el('div','empty-state');empty.append(el('span','','☑'),el('strong','','아직 표시할 작업이 없어요'),el('span','','문서 분석 결과에서 필요한 조치를 확인하고 등록해 보세요.'));$('tasks').append(empty);return;}
 const today=new Date().toLocaleDateString('en-CA');
 for(const task of tasks){const done=task.status==='DONE';const card=el('article','panel task-card');const check=el('button','task-check'+(done?' done':''),done?'✓':'');check.setAttribute('aria-label',done?'할 일로 되돌리기':'완료 처리');check.setAttribute('aria-pressed',done);check.addEventListener('click',async()=>{check.disabled=true;try{await api('/tasks/'+encodeURIComponent(task.id),{method:'PATCH',body:JSON.stringify({status:done?'TODO':'DONE'})});await refreshTasks();toast(done?'할 일로 되돌렸습니다.':'작업을 완료했습니다.');}catch(e){toast(e.message,true);check.disabled=false;}});const info=el('div','task-info'+(done?' done':''));info.append(el('h3','',task.title),el('p','',task.description));const meta=el('div','task-meta');meta.append(el('span',!done&&task.dueDate&&task.dueDate<today?'overdue':'',task.dueDate?'기한 '+task.dueDate:'기한 미지정'),el('span','',task.sourceName+' · '+sourcePage(task.page)));info.append(meta);const details=el('details','');details.append(el('summary','','등록 시 원문 근거'),el('blockquote','',task.quote));info.append(details);card.append(check,info);$('tasks').append(card);}
}
$('file-tab').addEventListener('click',()=>setInput('file'));$('text-tab').addEventListener('click',()=>setInput('text'));
$('file').addEventListener('change',e=>chooseFile(e.target.files[0]));$('remove-file').addEventListener('click',()=>{state.file=null;$('file').value='';$('file-preview').hidden=true;$('dropzone').hidden=false;invalidate();});
$('document-text').addEventListener('input',()=>{$('char-count').textContent=$('document-text').value.length.toLocaleString()+'자';invalidate();});
for(const event of ['dragover','dragenter'])$('dropzone').addEventListener(event,e=>{e.preventDefault();$('dropzone').classList.add('drag');});
for(const event of ['dragleave','drop'])$('dropzone').addEventListener(event,e=>{e.preventDefault();$('dropzone').classList.remove('drag');});
$('dropzone').addEventListener('drop',e=>{if(!state.busy)chooseFile(e.dataTransfer.files[0]);});
$('load-sample').addEventListener('click',()=>{setInput('text');$('document-text').value=sample;$('char-count').textContent=sample.length.toLocaleString()+'자';invalidate();toast('비식별 샘플을 입력했습니다. 문서 요약하기를 눌러 보세요.');});
$('analyze-button').addEventListener('click',analyze);
$('download-markdown').addEventListener('click',()=>{if(!state.doc)return;const blob=new Blob([state.doc.markdown],{type:'text/markdown;charset=utf-8'});const url=URL.createObjectURL(blob),a=el('a');a.href=url;a.download=state.doc.name.replace(/\.[^.]+$/,'')+'.md';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
$('back-to-context').addEventListener('click',()=>{$('context').scrollIntoView({behavior:'smooth',block:'center'});$('context').focus();});
$('close-dialog').addEventListener('click',()=>$('task-dialog').close());$('task-form').addEventListener('submit',saveTask);
$('new-analysis').addEventListener('click',()=>showView('analyze'));document.querySelectorAll('[data-view]').forEach(b=>b.addEventListener('click',()=>showView(b.dataset.view)));
$('task-filters').querySelectorAll('button').forEach(b=>b.addEventListener('click',()=>{state.taskFilter=b.dataset.status;for(const x of $('task-filters').children)x.classList.toggle('selected',x===b);renderTasks();}));
(async()=>{try{const config=await api('/config');state.csrf=config.csrf;state.aiConfigured=config.aiConfigured;updateAnalysisAvailability();await refreshTasks();}catch(e){$('input-error').textContent='서버에 연결하지 못했습니다. '+e.message;$('input-error').hidden=false;$('analyze-button').disabled=true;}})();

$('summary-purpose').addEventListener('change',()=>{
 const hints={GENERAL:'원문의 주요 문장과 조건을 항목별로 정리합니다.',BUSINESS:'요청 사항, 제출 자료, 기한과 담당 부서를 확인합니다.',SUPPORT:'지원 대상, 지원 내용, 신청 일정과 서류를 확인합니다.',CONTRACT:'요금, 변경 내용, 갱신·해지와 예외를 확인합니다.'};
 $('purpose-description').textContent=hints[$('summary-purpose').value];invalidate();
});

$('analysis-mode').addEventListener('change',()=>{$('consent').checked=false;updateAnalysisAvailability();invalidate();});
