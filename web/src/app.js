import {copyControl,sectionText,reportText} from './copy.js';
import {aiItems,aiView,aiSignedIn,initializeAi,promptLogin,requestAi,resetAi} from './ai.js';
import { SAMPLE_TEXT, SAMPLE_AI } from './sample.js';
import { PURPOSES, blocksFromText, validateDocument, summarize, location, reportMarkdown } from './summary.js';
import { readFile } from './read.js';
const $ = id => document.getElementById(id);
let mode = 'file', selected = null, busy = false, report = null, reading = null;
function node(tag, cls, text) { const el = document.createElement(tag); if (cls) el.className = cls; if (text !== undefined) el.textContent = text; return el; }
function invalidate() { resetAi(); report = null; $('results').hidden = true; $('ai-example').hidden = true; $('error').hidden = true; }
function error(message) { $('error').textContent = message; $('error').hidden = false; }
function setMode(value) {
  if (busy) return; mode = value; invalidate();
  for (const name of ['file', 'text']) { $(name + '-panel').hidden = name !== value; $(name + '-tab').setAttribute('aria-selected', String(name === value)); }
}
function choose(file) {
  if (busy || !file) return;
  if (file.size > 50 * 1024 * 1024) { error('파일은 50MB까지 지원합니다.'); return; }
  if (!/\.(pdf|hwp|html?|txt|md)$/i.test(file.name)) { error('PDF·HWP·HTML·TXT·MD 파일을 선택해 주세요. HWPX는 지원하지 않습니다.'); return; }
  selected = file; invalidate();
  $('pdf-options').hidden = !/\.pdf$/i.test(file.name);
  $('pdf-start').value = ''; $('pdf-end').value = ''; $('pdf-fast').checked = false;
  $('file-label').textContent = file.name; $('remove-file').hidden = false;
}
function setBusy(value) {
  busy = value; $('pdf-options').disabled = value;
  for (const id of ['summarize', 'ai-direct', 'file', 'file-tab', 'text-tab', 'document-text', 'purpose', 'sample', 'remove-file']) $(id).disabled = value;
  $('summarize').textContent = value ? '문서를 읽고 있어요…' : '문서 정리하기 →'; $('cancel').hidden = !value;
}
function evidence(block) { const el = node('div', 'evidence'); el.append(node('span', 'location', location(block)), node('div', '', block.text)); return el; }
function render(doc) {
  $('copy-all-slot').replaceChildren(copyControl(()=>reportText(report,aiItems()),'전체 복사','정리 결과 전체 복사'));
  $('result-meta').textContent = `${doc.name} · ${doc.pages ? `${doc.pages}쪽` : '원본 쪽 번호 없음'} · ${doc.characters.toLocaleString()}자 · ${PURPOSES[report.purpose][0]} · 무료 기본 정리`;
  $('warnings').replaceChildren(...report.warnings.map(w => node('p', '', w)));
  $('sections').replaceChildren();
  for (const section of report.sections) {
    const card = node('article', 'section-card'), heading=node('div','section-heading');
    heading.append(node('h3','',section.title),copyControl(()=>sectionText(section),'복사',section.title+' 복사'));card.append(heading);
    for (const block of section.excerpts) {
      const p = node('p', 'excerpt', block.text); p.append(node('span', 'location', location(block))); card.append(p);
    }
    if (!section.evidence.length) card.append(node('p', 'missing', '이 항목에 해당하는 원문을 찾지 못했습니다. 조건이 없다는 뜻은 아니므로 원문을 확인해 주세요.'));
    else if (!section.excerpts.length) card.append(node('p', 'missing', '조건·예외를 중간에 자르지 않도록 긴 항목은 아래 원문 근거에서 확인합니다.'));
    if (section.evidence.length) {
      const details = node('details'); details.append(node('summary', '', `전체 원문 근거 ${section.evidence.length}곳 · 발췌 ${section.excerpts.length}개`));
      details.addEventListener('toggle', () => { if (details.open && details.children.length === 1) { for (const block of section.evidence) details.append(evidence(block)); } }); card.append(details);
    }
    $('sections').append(card);
  }
  $('source').replaceChildren();
  const sourceDetails = $('source').parentElement; sourceDetails.open = false;
  sourceDetails.ontoggle = () => { if (sourceDetails.open && !$('source').children.length) { for (const block of doc.blocks) $('source').append(evidence(block)); } };
  $('results').hidden = false; $('results').focus({ preventScroll: true }); $('results').scrollIntoView({ behavior: 'smooth' });
}
$('file-tab').onclick = () => setMode('file'); $('text-tab').onclick = () => setMode('text');
$('pdf-options').oninput = invalidate;
$('file').onchange = event => choose(event.target.files[0]);
$('remove-file').onclick = () => { selected = null; $('pdf-options').hidden = true; $('file').value = ''; $('file-label').textContent = '파일을 선택하거나 여기에 놓으세요'; $('remove-file').hidden = true; invalidate(); };
$('document-text').oninput = () => { invalidate(); $('char-count').textContent = `${$('document-text').value.length.toLocaleString()} / 160,000자`; };
function showDocument(doc) {
  resetAi(doc, $('purpose').value); report = summarize(doc, $('purpose').value); render(doc);
}
function trySample() {
  if (busy) return; invalidate();
  const doc = validateDocument({ name: '체험용 샘플 · 지역기업 성장지원 사업 안내', pages: 0, blocks: blocksFromText(SAMPLE_TEXT), warnings: ['기능 체험용 가상 문서입니다. 실제 지원사업 공고가 아닙니다.'] });
  showDocument(doc);
  $('ai-example-items').replaceChildren(...aiView(SAMPLE_AI.map(item => ({ ...item, blocks: item.indexes.map(i => doc.blocks[i]) }))));
  $('ai-example').hidden = false;
  $('progress').textContent = '샘플 정리 완료 · 선택한 파일과 입력한 본문은 유지됩니다.';
}
$('sample').onclick = trySample;
$('purpose').onchange = () => { $('purpose-hint').textContent = PURPOSES[$('purpose').value][1]; invalidate(); };
for (const type of ['dragover', 'dragleave', 'drop']) $('dropzone').addEventListener(type, event => {
  event.preventDefault(); $('dropzone').classList.toggle('drag', type === 'dragover'); if (type === 'drop') choose(event.dataTransfer.files[0]);
});
// Reads the selected file or pasted text in the browser and shows the basic summary; returns the document or null.
async function readDocument() {
  if (mode === 'file' && !selected) { error('정리할 파일을 선택해 주세요.'); return null; }
  if (mode === 'text' && !$('document-text').value.trim()) { error('정리할 본문을 입력해 주세요.'); return null; }
  setBusy(true); $('progress').textContent = '본문과 원문 위치를 읽고 있어요…';
  try {
    reading = new AbortController();
    const doc = mode === 'file' ? await readFile(selected, value => { $('progress').textContent = value; }, reading.signal, {
      start: $('pdf-start').value || $('pdf-start').validity.badInput ? $('pdf-start').valueAsNumber : null,
      end: $('pdf-end').value || $('pdf-end').validity.badInput ? $('pdf-end').valueAsNumber : null,
      fast: $('pdf-fast').checked
    }) : validateDocument({ name: '붙여넣은 안내문', pages: 0, blocks: blocksFromText($('document-text').value), warnings: [] });
    showDocument(doc); $('progress').textContent = '정리 완료 · 문서를 서버로 전송하지 않았습니다.';
    return doc;
  } catch (reason) { error(reason.message || '문서를 읽지 못했습니다. 형식과 손상 여부를 확인해 주세요.'); $('progress').textContent = ''; return null; }
  finally { setBusy(false); }
}
$('summarize').onclick = async () => { if (busy) return; invalidate(); await readDocument(); };
// One click: check sign-in first so nothing is read in vain, then read the document and hand it to the AI summary.
$('ai-direct').onclick = async () => {
  if (busy) return; invalidate();
  if (!(await aiSignedIn())) { promptLogin(); return; }
  if (await readDocument()) requestAi();
};
$('cancel').onclick = () => reading?.abort(new Error('문서 읽기를 취소했습니다.'));
$('download').onclick = () => {
  if (!report) return;
  saveBlob(new Blob([reportMarkdown(report, aiItems())], {type:'text/markdown;charset=utf-8'}), 'ActionDoc-정리.md');
};
function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
void initializeAi();
