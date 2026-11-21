export const LIMITS = Object.freeze({ fileBytes: 50 * 1024 * 1024, pages: 50, characters: 160000 });
export const PURPOSES = {
  GENERAL: ['전체 정리', '주요 내용과 기한·금액·예외를 함께 확인합니다.'],
  BUSINESS: ['공문·업무 요청', '요청 사항, 제출 자료, 기한과 담당 부서를 확인합니다.'],
  SUPPORT: ['지원사업·모집 공고', '지원 대상, 지원 내용, 신청 일정과 서류를 확인합니다.'],
  CONTRACT: ['요금·계약 안내', '요금, 변경 내용, 갱신·해지와 예외를 확인합니다.']
};
const RULES = {
  GENERAL: [
    ['purpose', '문서 목적과 주요 내용', /목적|사업명|개요|추진|안내|내용|(?:지원|사업|모집)\s*대상|모집\s*인원/],
    ['action', '필요한 준비와 조치', /요청|회신|제출|협조|구비\s*(?:서류|자료)|첨부|붙임|신청.?방법|접수.?방법/],
    ['date', '기한과 주요 일정', /기한|마감|일정|기간|까지|일시|시행/],
    ['cost', '금액과 비용', /요금|수수료|지원금|지원.?금액|위약금|[0-9][0-9,]*\s*원/]
  ],
  BUSINESS: [
    ['action', '요청 사항과 제출 자료', /요청|회신|제출|협조|구비\s*(?:서류|자료)|첨부|붙임/],
    ['date', '기한과 주요 일정', /기한|마감|일정|기간|까지|일시/],
    ['contact', '담당 부서와 문의', /담당|문의|연락|전화|이메일|접수처/]
  ],
  SUPPORT: [
    ['eligibility', '지원 대상과 자격', /지원.?대상|신청.?대상|사업.?대상|신청.?자격|자격.?요건/],
    ['benefit', '지원 내용과 규모', /지원.?내용|지원.?금액|지원.?규모|지원금|지원율|대출.?한도/],
    ['date', '신청 기간과 일정', /신청.?기간|접수.?기간|모집.?기간|마감|발표|까지/],
    ['action', '신청 방법과 제출 서류', /신청.?방법|접수.?방법|제출|구비\s*(?:서류|자료)|신청서|접수처/]
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
// Leading symbols that mark list items and sections in Korean notices.
const bullets = /^[\s□■○●◦▪◆◇※*☞▶►•ㅇ#]+/;
const listMarker = /^(?:[□■○●◦▪◆◇※*☞▶►•·ㅇ\-–—]|[①-⑳➀-➉]|\(?\d{1,2}[.)]|[가-하][.)]|[【\[<〈「『])/;
// PDF text runs carry no spaces, and layout breaks split sentences; rebuild both from glyph positions.
export function pdfLines(items) {
  const lines = []; let line = null;
  for (const item of items) {
    const str = item.str?.replace(/\u0000/g, '');
    if (!str) continue;
    const [a, b, , , x, y] = item.transform, size = Math.hypot(a, b) || item.height || 10;
    // Same line while baselines differ by under a quarter glyph (table cells and superscripts sit further apart);
    // a gap wider than a fifth of the glyph is a word space.
    if (!line || Math.abs(y - line.y) > size * 0.25) lines.push(line = { text: '', left: x, right: x, y, gap: 0 });
    else {
      const gap = (x - line.right) / size;
      if (gap > 0.2 && !/\s$/.test(line.text) && !/^\s/.test(str)) line.text += ' ';
      line.gap = Math.max(line.gap, gap);
    }
    line.text += str; line.right = Math.max(line.right, x + item.width);
  }
  return lines;
}
// Joins lines {text,left,right,gap}; a letter-spaced heading is recognised from its text.
export function joinLines(lines) {
  if (!lines.length) return '';
  // Letter-spaced headings such as '사 업 명' read as one word, and never continue into the next line.
  const spaced = /(?<=^|\s)[가-힣](?: [가-힣]){2,}(?=[\s:：]|$)/g;
  const texts = lines.map(l => l.text.replace(/\s+/g, ' ').trim().replace(spaced, m => (l.spaced = true, m.replace(/ /g, ''))));
  const left = Math.min(...lines.map(l => l.left)), right = Math.max(...lines.map(l => l.right));
  const out = [];
  texts.forEach((text, i) => {
    const prev = lines[i - 1];
    // Prose that reaches the right margin continues on the next line. A gap of two or more glyphs inside a line
    // means columns (tables, contents pages with page numbers), whose lines never continue each other.
    const wrapped = prev && !prev.spaced && prev.gap < 2 && lines[i].gap < 2 && out.length && text && prev.right >= right - (right - left) * 0.12 &&
      /[가-힣A-Za-z0-9,·(]$/.test(out.at(-1)) && !/[.!?。]$|다$/.test(out.at(-1)) && !listMarker.test(text);
    if (wrapped) out[out.length - 1] += ' ' + text; else out.push(text);
  });
  return out.join('\n');
}
export const pdfPageText = items => joinLines(pdfLines(items));
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
    const line = block.text.replace(numbered, '').replace(bullets, '').trim();
    const label = supportLabels.find(([, re]) => re.test(line));
    const rest = label ? line.replace(label[1], '').replace(/^[\s:：-]+/, '') : line;
    // 'label: value' answers itself; a numbered or value-less label opens a section for the lines below it.
    const valued = /[:：]\s*\S/.test(line);
    if (label) context = numbered.test(block.text) || !valued ? label[0] : null;
    else if (numbered.test(block.text) || /^[□■]/.test(block.text)) context = null;
    if (!rest) continue;
    // '까지' alone also ends limits such as '최대 3세까지'; a schedule needs an actual date.
    const direct = RULES.SUPPORT.filter(([id, , re]) => re.test(line) && (id !== 'date' || dateValue.test(line))).map(([id]) => id);
    const ids = new Set(label ? [label[0]] :
      context === 'eligibility' && !direct.includes('date') ? [context] : direct.length ? direct : context ? [context] : []);
    if (EXCEPTIONS[2].test(line)) ids.add('exceptions');
    for (const id of ids) {
      if (!groups.has(id)) groups.set(id, []);
      // Show values first and section labels last; both stay in the evidence.
      groups.get(id).push({ block, rank: valued ? -1 : label ? 1 : 0 });
    }
  }
  return groups;
}
const contactValue = /[\w.+-]+@[\w.-]+\.[a-z]{2,}|0\d{1,2}[-)\s]+\d{3,4}[-\s]+\d{4}/i;
// Calendar forms only: year-month(-day), month-day, a day with its weekday, a clock time or a relative period.
const dateValue = /\d{4}\s*[.\/년-]\s*\d{1,2}(?!\d)|\d{1,2}\s*월\s*\d{1,2}\s*일|\d{1,2}\s*[.일]?\s*\(\s*[월화수목금토일]\s*\)|\d{1,2}:\d{2}|\d+\s*(?:일|개월|주|시간)\s*(?:이내|전|후|까지|간)/;
const material = /신청서|계획서|보고서|증명서|등록증|확인서|동의서|공문|명단|사본|서식/;
const actionVerb = /제출|회신|신청|접수|등록|첨부|송부|작성/;
const labels = {
  date: /^[가-힣]{0,4}\s*(?:기간|기한|마감|일정|일시)$/,
  contact: /^(?:문의(?:처)?|연락처|[가-힣]*담당(?:자|\s*부서)|전화|이메일)$/,
  action: /^(?:제출\s*(?:서류|자료)|구비\s*서류|요청\s*사항|신청\s*방법)$/
};
function contentBlocks(blocks) {
  // OCR may repeat a dotted contents entry on the same page with damaged leaders.
  const titles = new Set();
  const leader = /(?:[.·ㆍ…‥．]\s*){3,}(?:\d{1,3})?\s*$/;
  for (const b of blocks) {
    if (leader.test(b.text)) titles.add(b.page + ':' + b.text.replace(leader, '').replace(/\s/g, ''));
  }
  // A numbered line that starts with a contents title of the same page is that entry again, whatever follows it.
  return blocks.filter(b => {
    if (leader.test(b.text)) return false;
    const text = b.page + ':' + b.text.replace(/\s/g, '');
    return !/^\d+[.)]/.test(b.text.trim()) || ![...titles].some(t => text.startsWith(t));
  });
}
// A heading carries no value: no colon, no number and no predicate. It is short, or opens a numbered or □ section.
// Ordered sub-items such as '가. 지역 중소기업' are answers, however short.
const heading = text => {
  if (/[:：]/.test(text) || /^\s*(?:[가-하][.)]|[①-⑳➀-➉]|[-–·])/.test(text)) return false;
  const body = text.replace(bullets, '').replace(/^\d+[.)]?\s*/, '');
  if (/\d/.test(body) || /(?:다|요|함|음|임|됨)[.)\s]*$/.test(body)) return false;
  return body.replace(/\s/g, '').length <= 10 || /^\s*(?:\d+[.)]?\s|[□■])/.test(text);
};
// A table row: no predicate and more numeric cells than words, e.g. a row of amounts that lost its column labels.
const tableRow = text => {
  if (/(?:다|요)[.)\s]*$/.test(text)) return false;
  const cells = text.trim().split(/\s+/), numeric = cells.filter(c => /^\d/.test(c)).length;
  return numeric >= 3 && numeric > cells.length - numeric;
};
function factScore(id, text) {
  switch (id) {
    case 'purpose': return /^(?:사업명|사업목적|목적|(?:사업|지원|모집|신청)대상|모집인원|지원내용|주요내용)[:：]\S/.test(text.replace(bullets, '').replace(/\s/g, '')) ? 6 : /위해|위하여|목적으로/.test(text) ? 4 : 0;
    case 'date': return dateValue.test(text) ? 5 + Number(/접수|신청|마감|제출/.test(text)) : 0;
    case 'contact': return contactValue.test(text) ? 6 : /[가-힣]{2,}(?:팀|센터|사무국|지원단|과)(?:\s|$|[,.)])/.test(text) ? 3 : 0;
    case 'action': return Number(material.test(text)) * 3 + Number(actionVerb.test(text) && /하여|해야|합니다|하세요|바랍니다|까지|온라인|이메일|통해|회신해|제출해|접수해|필수/.test(text)) * 3;
    case 'exceptions': return /경우|경우에는|자는|기업은|시에는|되면|대상에서|할 수 없|할 수 있|됩니다|않습니다|불가$/.test(text) ? 4 : 0;
    case 'cost': return !tableRow(text) && /\d[\d,.]*\s*(?:천|만|억)?\s*(?:원|%)/.test(text) ? 5 : 0;
    default: return 0;
  }
}
function factCandidates(blocks, id, pattern) {
  const matches = [];
  for (let i = 0; i < blocks.length; i++) {
    const block = blocks[i];
    const label = block.text.replace(bullets, '').replace(/^(?:\d+[.)]\s*)?/, '').replace(/[:：\s]+$/, '');
    if (labels[id]?.test(label)) {
      const next = blocks[i + 1];
      if (next && next.page === block.page && factScore(id, next.text) && !/^\d+[.)]\s+[가-힣]/.test(next.text)) {
        if (!pattern.test(next.text)) {
          matches.push({ block: { ...block, id: block.id + '–' + next.id, text: block.text + ' · ' + next.text }, score: factScore(id, next.text) + 1 });
          i++;
        }
      }
      continue;
    }
    if (!pattern.test(block.text) && !(id === 'contact' && contactValue.test(block.text))) continue;
    const score = factScore(id, block.text);
    if (!score && heading(block.text)) continue;
    matches.push({ block, score });
  }
  return matches;
}
export function summarize(document, purpose = 'GENERAL') {
  if (!PURPOSES[purpose]) throw new Error('요약 목적을 다시 선택해 주세요.');
  const source = validateDocument(document);
  const rules = [...RULES[purpose], EXCEPTIONS];
  const blocks = contentBlocks(source.blocks);
  const grouped = purpose === 'SUPPORT' ? supportGroups(blocks) : null;
  const sections = rules.map(([id, title, pattern]) => {
    const entries = grouped?.get(id) || [];
    const facts = grouped ? [] : factCandidates(blocks, id, pattern);
    let evidence = grouped ? entries.map(e => e.block) : facts.map(f => f.block);
    // Without a purpose label, the first sentence-length lines usually state what the document is about.
    if (id === 'purpose' && !evidence.length) evidence = blocks.filter(b => b.text.length >= 20).slice(0, 3);
    // Keep source clauses intact; a cut sentence can lose its exception.
    const seen = new Set(); let length = 0;
    // Prefer lines that carry an actual value; bare headings and keyword-only matches stay in the evidence list.
    const scored = facts.some(f => f.score > 0) ? facts.filter(f => f.score > 0) : facts;
    const candidates = grouped ? [...entries].sort((a, b) => a.rank - b.rank).map(e => e.block) : scored.length ? scored.sort((a, b) => b.score - a.score).map(f => f.block) : evidence;
    const excerpts = candidates.filter(b => {
      if (b.text.length > 260 || seen.size >= 3 || length + b.text.length > 650 || seen.has(b.text) || heading(b.text)) return false;
      seen.add(b.text); length += b.text.length; return true;
    });
    if (!grouped) excerpts.sort((a, b) => a.page - b.page || parseInt(a.id.slice(1)) - parseInt(b.id.slice(1)));
    return { id, title, evidence, excerpts };
  });
  return { name: source.name, purpose, characters: source.characters, pages: source.pages, warnings: source.warnings || [], sections };
}
export function location(block) { return block.page > 0 ? `${block.page}쪽 · ${block.id}` : `본문 · ${block.id}`; }
export function reportMarkdown(report, ai = []) {
  return [`# ${report.name}`, `목적: ${PURPOSES[report.purpose][0]}`,
    ...(ai.length ? ['## AI 상세 요약', '원문 근거 검증을 통과한 AI 요약입니다. 금액과 기한은 인용된 원문과 함께 확인해 주세요.',
      // Same grouping as the screen: one heading per section the model chose, in order of first appearance.
      ...[...ai.reduce((groups, i) => groups.set(i.section, [...(groups.get(i.section) ?? []), i]), new Map())].map(([section, items]) =>
        [...(section ? [`### ${section}`] : []), ...items.map(i => [`- ${i.statement}`, ...i.blocks.map(b => `  > 원문 근거 (${location(b)}): ${b.text}`)].join('\n'))].join('\n\n')),
      '## 무료 기본 정리'] : []),
    '무료 기본 정리 · AI 문맥 요약이 아닌 원문 발췌입니다.',
    ...report.warnings.map(w => `주의: ${w}`), ...report.sections.flatMap(s => [
      `\n## ${s.title}`, ...(s.excerpts.length ? s.excerpts.map(b => `- ${b.text} (${location(b)})`) : ['짧게 표시할 원문이 없습니다. 아래 근거를 확인해 주세요.']),
      `\n### 전체 원문 근거 (${s.evidence.length}곳)`, ...s.evidence.map(b => `- [${location(b)}] ${b.text}`)
    ])].join('\n\n');
}
