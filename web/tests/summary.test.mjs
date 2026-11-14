import test from 'node:test';
import assert from 'node:assert/strict';
import { blocksFromText, summarize, reportMarkdown, validateDocument } from '../src/summary.js';
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
