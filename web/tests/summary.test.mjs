import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { blocksFromText, joinLines, pdfPageText, summarize, reportMarkdown, validateDocument } from '../src/summary.js';
const source = { name: '공문.txt', pages: 0, blocks: blocksFromText('지원 대상: 지역 중소기업\n지원 내용: 교육비 50,000원\n2027년 11월 25일까지 보고서를 제출해 주세요.\n담당 부서: 운영팀\n월 요금은 33,000원입니다.\n계약 해지 시 위약금이 있습니다.\n단, 신규 신청자는 제외됩니다.'), warnings: [] };
test('shared basic-summary fixture', () => {
  for (const c of JSON.parse(readFileSync(new URL('../../shared/fixtures/basic-summary.json', import.meta.url))).cases) {
    const sections = summarize({ ...source, pages: c.page, blocks: blocksFromText(c.text, c.page) }, c.purpose).sections;
    assert.deepEqual(Object.fromEntries(sections.map(s => [s.id, s.excerpts.map(b => b.text)])), c.expect, c.name);
    assert.deepEqual(sections.map(s => s.id), Object.keys(c.expect), c.name);
    for (const [id, texts] of Object.entries(c.evidence ?? {})) assert.deepEqual(sections.find(s => s.id === id).evidence.map(b => b.text), texts, c.name);
    for (const [id, re] of Object.entries(c.evidenceExcludes ?? {})) assert.ok(sections.find(s => s.id === id).evidence.every(b => !new RegExp(re).test(b.text)), c.name);
  }
  assert.throws(() => summarize(source, 'WRONG'));
  assert.ok(reportMarkdown(summarize(source, 'CONTRACT')).includes('33,000원'));
  for (const purpose of ['BUSINESS', 'SUPPORT', 'CONTRACT']) {
    const report = summarize(source, purpose);
    assert.ok(reportMarkdown(report).includes('원문 근거'), purpose);
    // Excerpts are the source blocks themselves, never rewritten copies.
    for (const section of report.sections) for (const block of section.excerpts) assert.ok(source.blocks.includes(block), purpose);
  }
});
test('length and empty checks match desktop boundaries', () => {
  assert.throws(() => validateDocument({ blocks: [] }));
  assert.equal(validateDocument({ blocks: blocksFromText('가'.repeat(160000)) }).characters, 160000);
  assert.throws(() => validateDocument({ blocks: blocksFromText('가'.repeat(160001)) }));
});
test('saved report includes verified AI items with their evidence only when present', () => {
  const report = summarize(source, 'GENERAL');
  assert.ok(!reportMarkdown(report).includes('AI 상세 요약'));
  const markdown = reportMarkdown(report, [{ statement: '교육비 50,000원을 지원합니다.', blocks: [source.blocks[1], source.blocks[2]] }]);
  assert.ok(markdown.indexOf('AI 상세 요약') < markdown.indexOf('무료 기본 정리'));
  assert.ok(markdown.includes(`- 교육비 50,000원을 지원합니다.\n  > 원문 근거 (본문 · b2): ${source.blocks[1].text}\n  > 원문 근거 (본문 · b3): ${source.blocks[2].text}`));
  // Items the model filed under the same section stay together even when they arrive apart.
  const grouped = reportMarkdown(report, [{ section: '지원 내용', statement: 'A', blocks: [source.blocks[1]] }, { section: '일정', statement: 'B', blocks: [source.blocks[2]] }, { section: '지원 내용', statement: 'C', blocks: [source.blocks[1]] }]);
  assert.ok(/### 지원 내용\n\n- A[\s\S]*- C[\s\S]*### 일정\n\n- B/.test(grouped));
});
test('PDF text keeps real word gaps, rejoins wrapped lines and keeps list items apart', () => {
  // One run per glyph group, positioned like pdf.js text content (font size 10, x/width in page units).
  const run = (str, x, y, width = str.length * 10) => ({ str, transform: [10, 0, 0, 10, x, y], width, height: 10 });
  const text = pdfPageText([
    run('사', 0, 800), run('업', 40, 800), run('명', 80, 800), run(': 지원사업', 90, 800),
    run('(', 0, 780), run('필수', 10, 780), run(')', 30, 780), run('동의서를', 45, 780), run('서명 후 스캔 또는 사진파일로', 90, 780, 400), run('제출하고,', 495, 780, 90),
    run('담당 부서에 회신합니다.', 0, 760),
    run('□ 신청기간: 2026. 5. 11.', 0, 740, 585),
    run('○ 신청방법: 온라인', 0, 720),
    // A contents page: titles on the left, page references aligned to the right margin.
    run('교육 지원 안내', 0, 700), run('3', 575, 700, 10), run('신청 서류 목록', 0, 680), run('7', 575, 680, 10),
    // A table row reaching the margin does not continue into the next row either.
    run('구분', 0, 660, 40), run('금액', 300, 660, 40), run('비고', 545, 660, 40), run('일반', 0, 640, 40)
  ]);
  assert.deepEqual(text.split('\n'), ['사업명: 지원사업', '(필수) 동의서를 서명 후 스캔 또는 사진파일로 제출하고, 담당 부서에 회신합니다.',
    '□ 신청기간: 2026. 5. 11.', '○ 신청방법: 온라인', '교육 지원 안내 3', '신청 서류 목록 7', '구분 금액 비고', '일반']);
});
test('shared line-joining fixture', () => {
  for (const c of JSON.parse(readFileSync(new URL('../../shared/fixtures/line-joining.json', import.meta.url))).cases)
    assert.deepEqual(joinLines(c.lines).split('\n'), c.expected, c.name);
});
