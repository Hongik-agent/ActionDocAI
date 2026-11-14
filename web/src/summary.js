export const LIMITS = Object.freeze({ fileBytes: 50 * 1024 * 1024, pages: 50, characters: 160000 });
export const PURPOSES = {
  GENERAL: ['전체 정리', '주요 내용과 기한·금액·예외를 함께 확인합니다.'],
  BUSINESS: ['공문·업무 요청', '요청 사항, 제출 자료, 기한과 담당 부서를 확인합니다.'],
  SUPPORT: ['지원사업·모집 공고', '지원 대상, 지원 내용, 신청 일정과 서류를 확인합니다.'],
  CONTRACT: ['요금·계약 안내', '요금, 변경 내용, 갱신·해지와 예외를 확인합니다.']
};
const RULES = {
  GENERAL: [
    ['purpose', '문서 목적과 주요 내용', /목적|사업명|개요|추진|안내|내용|지원대상|지원 대상/],
    ['action', '필요한 준비와 조치', /요청|회신|제출|협조|구비|첨부|붙임|신청.?방법|접수.?방법/],
    ['date', '기한과 주요 일정', /기한|마감|일정|기간|까지|일시|시행/],
    ['cost', '금액과 비용', /요금|수수료|지원금|지원.?금액|위약금|[0-9][0-9,]*\s*원/]
  ],
  BUSINESS: [
    ['action', '요청 사항과 제출 자료', /요청|회신|제출|협조|구비|첨부|붙임/],
    ['date', '기한과 주요 일정', /기한|마감|일정|기간|까지|일시/],
    ['contact', '담당 부서와 문의', /담당|문의|연락|전화|이메일|접수처/]
  ],
  SUPPORT: [
    ['eligibility', '지원 대상과 자격', /지원.?대상|신청.?대상|사업.?대상|신청.?자격|자격.?요건/],
    ['benefit', '지원 내용과 규모', /지원.?내용|지원.?금액|지원.?규모|지원금|지원율|대출.?한도/],
    ['date', '신청 기간과 일정', /신청.?기간|접수.?기간|모집.?기간|마감|발표|까지/],
    ['action', '신청 방법과 제출 서류', /신청.?방법|접수.?방법|제출|구비|신청서|접수처/]
  ],
  CONTRACT: [
    ['cost', '요금과 비용', /요금|수수료|보험료|청구|부가세|할인|위약금|납부|[0-9][0-9,]*\s*원/],
    ['change', '변경 내용과 적용 시점', /변경|인상|인하|기존|신설|개정|종전|적용|시행/],
    ['termination', '계약 기간·갱신·해지', /계약.?기간|약정|갱신|해지|환불|철회/]
  ]
};
const EXCEPTIONS = ['exceptions', '제한·예외와 주의 사항', /제외|예외|단서|다만|유의|주의|불가|취소|불이익|미납|미제출|하지\s*않|아니/];
export function blocksFromText(text, page = 0, offset = 0) {
  return text.replace(/\u0000/g, '').split(/\r?\n|\r/).map(t => t.replace(/[\t\f ]+/g, ' ').trim()).filter(Boolean)
    .map((text, i) => ({ id: `b${offset + i + 1}`, page, text }));
}
export function validateDocument(document) {
  const size = document.blocks.reduce((sum, b) => sum + b.text.length, 0);
  if (!size) throw new Error('읽을 수 있는 본문이 없습니다. 원본 상태를 확인하거나 텍스트를 붙여넣어 주세요.');
  if (size > LIMITS.characters) throw new Error('본문은 160,000자까지 지원합니다. 문서를 나누어 입력해 주세요.');
  return { ...document, characters: size };
}
// A notice often puts the label on one PDF line and the answer on the next.
const supportLabels = [
  ['eligibility', /^(?:(?:지원|신청|모집|참여)\s*(?:대상|자격|요건)|자격\s*요건)/],
  ['benefit', /^지원\s*(?:내용|규모|금액|혜택)/],
  ['date', /^(?:(?:신청|접수|모집)\s*기간|(?:선정|추진)\s*일정|선정\s*발표)/],
  ['action', /^(?:(?:신청|접수)\s*방법|제출\s*서류|접수처)/],
  ['exceptions', /^(?:유의\s*사항|주의\s*사항|제외\s*대상)/]
];
function supportGroups(blocks) {
  const groups = new Map();
  let context = null;
  for (const block of blocks) {
    const numbered = /^\s*(?:제?\d+[.)]|[Ⅰ-Ⅹ]+[.])\s*/;
    const line = block.text.replace(numbered, '').replace(/^[□■○●▶#]+\s*/, '').trim();
    const label = supportLabels.find(([, re]) => re.test(line));
    const rest = label ? line.replace(label[1], '').replace(/^[\s:：-]+/, '') : line;
    if (label) context = !rest || numbered.test(block.text) || /^[（(]/.test(rest) ? label[0] : null;
    else if (numbered.test(block.text) || /^[□■]/.test(block.text)) context = null;
    if (!rest) continue;
    const fits = /(?:가능한|등록한|보유한|소재한|해당하는|중인)\s*(?:기업|기관|단체|자)\s*[.)]?$/u.test(line);
    const direct = RULES.SUPPORT.filter(([, , re]) => re.test(line)).map(([id]) => id);
    const ids = new Set(fits ? ['eligibility'] : label ? [label[0]] :
      context === 'eligibility' && !direct.includes('date') ? [context] : direct.length ? direct : context ? [context] : []);
    if (EXCEPTIONS[2].test(line)) ids.add('exceptions');
    for (const id of ids) {
      if (!groups.has(id)) groups.set(id, []);
      // Preserve qualified titles as evidence, but show the actual track details first.
      groups.get(id).push({ block, rank: label && /^[（(]/.test(rest) ? 1 : 0 });
    }
  }
  return groups;
}
export function summarize(document, purpose = 'GENERAL') {
  if (!PURPOSES[purpose]) throw new Error('요약 목적을 다시 선택해 주세요.');
  const source = validateDocument(document);
  const rules = [...RULES[purpose], EXCEPTIONS];
  const grouped = purpose === 'SUPPORT' ? supportGroups(source.blocks) : null;
  const sections = rules.map(([id, title, pattern]) => {
    const entries = grouped?.get(id) || [];
    let evidence = grouped ? entries.map(e => e.block) : source.blocks.filter(b => pattern.test(b.text));
    if (id === 'purpose' && !evidence.length) evidence = source.blocks.filter(b => b.text.length >= 20).slice(0, 3);
    // Keep source clauses intact; a cut sentence can lose its exception.
    const seen = new Set(); let length = 0;
    const candidates = grouped ? [...entries].sort((a, b) => a.rank - b.rank).map(e => e.block) : evidence;
    const excerpts = candidates.filter(b => {
      if (b.text.length > 260 || seen.size >= 3 || length + b.text.length > 650 || seen.has(b.text)) return false;
      seen.add(b.text); length += b.text.length; return true;
    });
    return { id, title, evidence, excerpts };
  });
  return { name: source.name, purpose, characters: source.characters, pages: source.pages, warnings: source.warnings || [], sections };
}
export function location(block) { return block.page > 0 ? `${block.page}쪽 · ${block.id}` : `본문 · ${block.id}`; }
export function reportMarkdown(report) {
  return [`# ${report.name}`, `목적: ${PURPOSES[report.purpose][0]}`, '무료 기본 정리 · AI 문맥 요약이 아닌 원문 발췌입니다.',
    ...report.warnings.map(w => `주의: ${w}`), ...report.sections.flatMap(s => [
      `\n## ${s.title}`, ...(s.excerpts.length ? s.excerpts.map(b => `- ${b.text} (${location(b)})`) : ['짧게 표시할 원문이 없습니다. 아래 근거를 확인해 주세요.']),
      `\n### 전체 원문 근거 (${s.evidence.length}곳)`, ...s.evidence.map(b => `- [${location(b)}] ${b.text}`)
    ])].join('\n\n');
}
