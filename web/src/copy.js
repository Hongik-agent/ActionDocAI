import {PURPOSES,location} from './summary.js';

export function sectionText(section) {
  const blocks=section.excerpts.length?section.excerpts:section.evidence;
  return [section.title,...(blocks.length?blocks.map(b=>`• ${b.text} (${location(b)})`):
    ['해당 항목의 원문을 찾지 못했습니다. 조건이 없다는 뜻은 아니므로 원문을 확인해 주세요.'])].join('\n');
}
export function aiText(items) {
  let heading;
  return items.flatMap(item=>{
    const title=item.section!==heading?item.section:'';heading=item.section;
    return [...(title?[title]:[]),`• ${item.statement}`,`  원문 위치: ${item.blocks.map(location).join(', ')}`];
  }).join('\n');
}
export function reportText(report,items=[]) {
  return [report.name,`목적: ${PURPOSES[report.purpose][0]}`,
    ...report.warnings.map(w=>`주의: ${w}`),
    ...(items.length?['AI 상세 요약',aiText(items)]:[]),
    '기본 정리 · 원문 발췌',...report.sections.map(sectionText),
    '금액·기한·예외는 원본 문서와 함께 확인해 주세요.'].join('\n\n');
}
export function copyControl(getText,label='복사',description=label) {
  const wrapper=document.createElement('span'),button=document.createElement('button'),status=document.createElement('span');
  wrapper.className='copy-control';button.type='button';button.className='secondary copy-button';button.textContent=label;
  button.setAttribute('aria-label',description);status.className='copy-feedback';status.setAttribute('role','status');
  button.onclick=async()=>{
    button.disabled=true;status.textContent='';
    try {
      await navigator.clipboard.writeText(getText());
      status.textContent='복사 완료';
    } catch { status.textContent='복사하지 못했습니다. 브라우저의 클립보드 권한을 확인해 주세요.'; }
    finally { button.disabled=false; }
  };
  wrapper.append(button,status);return wrapper;
}
