import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { blocksFromText, joinLines, pdfPageText, summarize, reportMarkdown, validateDocument } from '../src/summary.js';
const source = { name: '공문.txt', pages: 0, blocks: blocksFromText('지원 대상: 지역 중소기업\n지원 내용: 교육비 50,000원\n2027년 11월 25일까지 보고서를 제출해 주세요.\n담당 부서: 운영팀\n월 요금은 33,000원입니다.\n계약 해지 시 위약금이 있습니다.\n단, 신규 신청자는 제외됩니다.'), warnings: [] };
test('purpose changes grouping, with exact evidence and exceptions retained', () => {
  for (const [purpose, first] of [['BUSINESS', 'action'], ['SUPPORT', 'eligibility'], ['CONTRACT', 'cost']]) {
    const report = summarize(source, purpose); assert.equal(report.sections[0].id, first);
    assert.equal(report.sections.at(-1).excerpts[0].text, '단, 신규 신청자는 제외됩니다.');
    for (const section of report.sections) for (const block of section.excerpts) assert.ok(source.blocks.includes(block));
    assert.ok(reportMarkdown(report).includes('33,000원') || purpose !== 'CONTRACT');
    assert.ok(reportMarkdown(report).includes('원문 근거'));
  }
});
test('long clauses do not flood the preview or lose their original evidence', () => {
  const text = '다만, ' + '예외 조건을 확인합니다. '.repeat(100);
  const report = summarize({ ...source, blocks: blocksFromText(text) });
  const section = report.sections.at(-1); assert.equal(section.excerpts.length, 0); assert.equal(section.evidence[0].text, text.trim());
});
test('missing information never creates invented costs or dates', () => {
  const report = summarize({ ...source, blocks: blocksFromText('오늘은 화창한 날씨입니다.') }, 'CONTRACT');
  assert.ok(report.sections.every(s => !s.evidence.length && !s.excerpts.length));
  assert.throws(() => summarize(source, 'WRONG'));
});
test('length and empty checks match desktop boundaries', () => {
  assert.throws(() => validateDocument({ blocks: [] }));
  assert.equal(validateDocument({ blocks: blocksFromText('가'.repeat(160000)) }).characters, 160000);
  assert.throws(() => validateDocument({ blocks: blocksFromText('가'.repeat(160001)) }));
});
test('notice headings group their answers and keep eligibility out of submission instructions', () => {
  const blocks = blocksFromText('3. 신청 자격\n가. 지역 중소기업\n다. 대면 자문회의 참석 및 사전 자료 제출이 가능한 기업\n4. 지원 내용 (2 개 트랙) / 중복 신청 가능\n트랙 A: 전문가 자문 3회\n트랙 B: 시제품 제작비 500,000원\n5. 사업 배경\n지역 산업의 발전\n7. 신청 방법\n가. 제출서류 : 참여신청서 1부');
  const sections = Object.fromEntries(summarize({ ...source, blocks }, 'SUPPORT').sections.map(s => [s.id, s]));
  assert.deepEqual(sections.eligibility.excerpts, blocks.slice(1, 3));
  assert.deepEqual(sections.action.excerpts, [blocks[9]]);
  assert.deepEqual(sections.benefit.excerpts, [blocks[4], blocks[5], blocks[3]]);
});
test('business preview finds actionable facts beyond contents, damaged OCR and form labels', () => {
  // Contents entries repeated with arbitrary OCR debris, a word that merely contains '구비', and a prefixed contact label.
  const blocks = blocksFromText('연구비 집행 계획\n사업기간\n4. 신청방법 및 제출서류 · · · · 7\n5. 접수기간 및 유의사항 · · · 10\n5. 접수기간 및 유의사항 %% 4 ;\n9. 문의처 · · · 21\n9. 문의처 Qx ≈≈ ]]\n사업총괄담당자\n□ 사업기간 및 사업비 (지자체 부담) 규모\n사업계획서와 확인서를 이메일로 제출해야 합니다.\n접수 마감\n2028. 6. 30. 17:00까지\n문의처\n정비지원과 031-000-0000\n서류가 빠지면 선정 대상에서 제외됩니다.', 3);
  const result = summarize({ ...source, blocks }, 'BUSINESS').sections;
  for (const [i, expected] of ['사업계획서', '2028. 6. 30.', '031-000-0000', '빠지면'].entries()) {
    assert.ok(result[i].excerpts[0].text.includes(expected));
    assert.ok(result[i].evidence.every(b => !/연구비|%%|≈≈|· · ·/.test(b.text)));
  }
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
test('excerpts skip bare headings, prefer labelled values and do not read amounts as dates', () => {
  const blocks = blocksFromText('1 개요\n□ 세부사항\n□ 사업명: 마을 정비 지원\n□ 모집인원: 15곳\n- 공사비(3.2억원 이하)의 80% 범위에서 최대 5천만원까지 가능\n□ 접수기간: 2027. 4. 1.(목) 09:00 ~ 15.(목) 17:00\n구분 1단계 120,000원 240,000원 360,000원\n- 자부담금: 1곳당 500,000원\n지원금은 1곳 100만원, 2곳 150만원, 3곳 200만원입니다.');
  const sections = Object.fromEntries(summarize({ ...source, blocks }, 'GENERAL').sections.map(s => [s.id, s.excerpts.map(b => b.text)]));
  assert.deepEqual(sections.purpose, ['□ 사업명: 마을 정비 지원', '□ 모집인원: 15곳']);
  assert.deepEqual(sections.date, ['□ 접수기간: 2027. 4. 1.(목) 09:00 ~ 15.(목) 17:00']);
  // A row of amounts without labels is left out; a sentence listing several amounts is not a table row.
  assert.deepEqual(sections.cost, ['- 자부담금: 1곳당 500,000원', '지원금은 1곳 100만원, 2곳 150만원, 3곳 200만원입니다.']);
});
