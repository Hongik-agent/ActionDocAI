import CFB from 'cfb';
import { Inflate } from 'pako';
const SIGNATURE = [0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1];
const MAX_EXPANDED = 32 * 1024 * 1024;
const invalid = () => new Error('HWP 본문을 읽지 못했습니다. 손상·보호 여부를 확인하거나 PDF·TXT로 다시 저장해 주세요.');
export function extractHwp(data) {
  if (!(data instanceof Uint8Array) || data.length < 512 || !SIGNATURE.every((b, i) => data[i] === b))
    throw new Error('HWP 5 형식만 지원합니다. HWPX는 PDF·TXT로 변환해 주세요.');
  const ole = CFB.read(data, { type: 'array' });
  const entry = CFB.find(ole, '/FileHeader');
  const header = entry?.content ? Uint8Array.from(entry.content) : null;
  if (!header || header.length !== 256 || new TextDecoder().decode(header.slice(0, 17)) !== 'HWP Document File' || header[35] !== 5) throw invalid();
  const flags = new DataView(header.buffer, header.byteOffset, header.byteLength).getUint32(36, true);
  if (flags & (2 | 4 | 16 | 256 | 1024)) throw new Error('암호·배포용·DRM·인증서 보호 HWP는 지원하지 않습니다. 보호되지 않은 원본을 사용해 주세요.');
  const sections = ole.FullPaths.map((path, i) => ({ path, entry: ole.FileIndex[i] }))
    .filter(x => /\/BodyText\/Section\d+$/.test(x.path)).sort((a, b) => Number(a.path.match(/\d+$/)[0]) - Number(b.path.match(/\d+$/)[0]));
  if (!sections.length) throw invalid();
  let expanded = 0, count = 0; const paragraphs = [];
  for (const section of sections) {
    let bytes = Uint8Array.from(section.entry.content);
    if (flags & 1) {
      const chunks = []; const inflater = new Inflate({ raw: true, chunkSize: 8192 });
      inflater.onData = chunk => { expanded += chunk.length; if (expanded > MAX_EXPANDED) throw new Error('HWP 본문 압축 해제 크기가 32MB를 넘습니다. 문서를 나누어 주세요.'); chunks.push(chunk); };
      inflater.push(bytes, true); if (inflater.err || !inflater.ended) throw invalid();
      bytes = new Uint8Array(chunks.reduce((s, c) => s + c.length, 0)); let at = 0;
      for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.length; }
    } else { expanded += bytes.length; if (expanded > MAX_EXPANDED) throw invalid(); }
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength); let at = 0;
    while (at < bytes.length) {
      if (at + 4 > bytes.length) throw invalid();
      const head = view.getUint32(at, true); at += 4; const tag = head & 1023; let size = head >>> 20;
      if (size === 4095) { if (at + 4 > bytes.length) throw invalid(); size = view.getUint32(at, true); at += 4; }
      if (at + size > bytes.length) throw invalid();
      if (tag === 67) {
        if (size % 2) throw invalid(); let text = '';
        for (let i = at; i < at + size; i += 2) {
          const c = view.getUint16(i, true);
          if (c === 10 || c === 13) text += '\n';
          else if (c === 9 || (c >= 1 && c <= 8) || (c >= 11 && c <= 12) || (c >= 14 && c <= 23)) {
            if (i + 16 > at + size) throw invalid(); text += c === 9 ? '\t' : ' '; i += 14;
          } else if (c >= 32) text += String.fromCharCode(c);
        }
        count += text.length; if (count > 160000) throw new Error('본문은 160,000자까지 지원합니다. 문서를 나누어 주세요.'); paragraphs.push(text);
      }
      at += size;
    }
  }
  if (!paragraphs.join('').trim()) throw new Error('HWP에서 텍스트를 찾지 못했습니다. 이미지 문서는 Mac 앱을 사용해 주세요.');
  return paragraphs.join('\n');
}
