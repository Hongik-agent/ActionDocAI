import { PURPOSES, blocksFromText, validateDocument, summarize, location, reportMarkdown } from './summary.js';
import { readFile } from './read.js';
const $ = id => document.getElementById(id);
let mode = 'file', selected = null, busy = false, report = null;
const sample = `지역기업 성장지원 사업 안내\n지원 대상: 지역 내 사업장을 둔 중소기업입니다.\n지원 내용: 선정 기업에 교육비 최대 500,000원을 지원합니다.\n신청 기간: 2027년 3월 1일부터 2027년 3월 20일까지입니다.\n신청 방법: 신청서와 사업자등록증을 운영팀에 제출해 주세요.\n단, 동일 항목으로 다른 지원금을 받은 기업은 제외됩니다.\n제출 서류가 누락되면 심사 대상에서 제외될 수 있습니다.\n문의: 기업지원팀, 02-0000-0000\n※ 기능 체험용 가상 문서입니다.`;
function node(tag, cls, text) { const el = document.createElement(tag); if (cls) el.className = cls; if (text !== undefined) el.textContent = text; return el; }
function invalidate() { report = null; $('results').hidden = true; $('error').hidden = true; }
function error(message) { $('error').textContent = message; $('error').hidden = false; }
function setMode(value) {
  if (busy) return; mode = value; invalidate();
  for (const name of ['file', 'text']) { $(name + '-panel').hidden = name !== value; $(name + '-tab').setAttribute('aria-selected', String(name === value)); }
}
function choose(file) {
  if (busy || !file) return;
  if (file.size > 50 * 1024 * 1024) { error('파일은 50MB까지 지원합니다.'); return; }
  if (!/\.(pdf|hwp|html?|txt|md)$/i.test(file.name)) { error('PDF·HWP·HTML·TXT·MD 파일을 선택해 주세요. HWPX는 지원하지 않습니다.'); return; }
  selected = file; invalidate(); $('file-label').textContent = file.name; $('remove-file').hidden = false;
}
function setBusy(value) {
  busy = value;
  for (const id of ['summarize', 'file', 'file-tab', 'text-tab', 'document-text', 'purpose', 'sample', 'remove-file']) $(id).disabled = value;
  $('summarize').textContent = value ? '문서를 읽고 있어요…' : '문서 정리하기 →';
}
function evidence(block) { const el = node('div', 'evidence'); el.append(node('span', 'location', location(block)), node('div', '', block.text)); return el; }
function render(doc) {
  $('result-meta').textContent = `${doc.name} · ${doc.pages ? `${doc.pages}쪽` : '원본 쪽 번호 없음'} · ${doc.characters.toLocaleString()}자 · ${PURPOSES[report.purpose][0]} · 무료 기본 정리`;
  $('warnings').replaceChildren(...report.warnings.map(w => node('p', '', w)));
  $('sections').replaceChildren();
  for (const section of report.sections) {
    const card = node('article', 'section-card'); card.append(node('h3', '', section.title));
    for (const block of section.excerpts) {
      const p = node('p', 'excerpt', block.text); p.append(node('span', 'location', location(block))); card.append(p);
    }
    if (!section.evidence.length) card.append(node('p', 'missing', '이 항목에 해당하는 원문을 찾지 못했습니다. 조건이 없다는 뜻은 아니므로 원문을 확인해 주세요.'));
    else if (!section.excerpts.length) card.append(node('p', 'missing', '조건·예외를 중간에 자르지 않도록 긴 항목은 아래 원문 근거에서 확인합니다.'));
    if (section.evidence.length) {
      const details = node('details'); details.append(node('summary', '', `전체 원문 근거 ${section.evidence.length}곳 · 발췌 ${section.excerpts.length}개`));
      details.addEventListener('toggle', () => { if (details.open && details.children.length === 1) details.append(...section.evidence.map(evidence)); }); card.append(details);
    }
    $('sections').append(card);
  }
  $('source').replaceChildren();
  const sourceDetails = $('source').parentElement; sourceDetails.open = false;
  sourceDetails.ontoggle = () => { if (sourceDetails.open && !$('source').children.length) $('source').append(...doc.blocks.map(evidence)); };
  $('results').hidden = false; $('results').focus({ preventScroll: true }); $('results').scrollIntoView({ behavior: 'smooth' });
}
$('file-tab').onclick = () => setMode('file'); $('text-tab').onclick = () => setMode('text');
$('file').onchange = event => choose(event.target.files[0]);
$('remove-file').onclick = () => { selected = null; $('file').value = ''; $('file-label').textContent = '파일을 선택하거나 여기에 놓으세요'; $('remove-file').hidden = true; invalidate(); };
$('document-text').oninput = () => { invalidate(); $('char-count').textContent = `${$('document-text').value.length.toLocaleString()} / 160,000자`; };
$('sample').onclick = () => { $('document-text').value = sample; $('document-text').oninput(); };
$('purpose').onchange = () => { $('purpose-hint').textContent = PURPOSES[$('purpose').value][1]; invalidate(); };
for (const type of ['dragover', 'dragleave', 'drop']) $('dropzone').addEventListener(type, event => {
  event.preventDefault(); $('dropzone').classList.toggle('drag', type === 'dragover'); if (type === 'drop') choose(event.dataTransfer.files[0]);
});
$('summarize').onclick = async () => {
  if (busy) return; invalidate();
  if (mode === 'file' && !selected) { error('정리할 파일을 선택해 주세요.'); return; }
  if (mode === 'text' && !$('document-text').value.trim()) { error('정리할 본문을 입력해 주세요.'); return; }
  setBusy(true); $('progress').textContent = '본문과 원문 위치를 읽고 있어요…';
  try {
    const doc = mode === 'file' ? await readFile(selected, value => { $('progress').textContent = value; }) : validateDocument({ name: '붙여넣은 안내문', pages: 0, blocks: blocksFromText($('document-text').value), warnings: [] });
    report = summarize(doc, $('purpose').value); render(doc); $('progress').textContent = '정리 완료 · 문서를 서버로 전송하지 않았습니다.';
  } catch (reason) { error(reason.message || '문서를 읽지 못했습니다. 형식과 손상 여부를 확인해 주세요.'); $('progress').textContent = ''; }
  finally { setBusy(false); }
};
$('download').onclick = () => {
  if (!report) return;
  const url = URL.createObjectURL(new Blob([reportMarkdown(report)], { type: 'text/markdown;charset=utf-8' }));
  const link = document.createElement('a'); link.href = url; link.download = 'ActionDoc-정리.md'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
};
