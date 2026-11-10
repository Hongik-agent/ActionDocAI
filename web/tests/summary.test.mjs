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
