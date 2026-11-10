import CFB from 'cfb';
import { deflateRaw } from 'pako';
export function hwpFixture(text = '지원 대상은 지역 중소기업입니다.\n제출 기한을 확인해 주세요.', flags = 1) {
  const ole = CFB.utils.cfb_new();
  const header = new Uint8Array(256); header.set(new TextEncoder().encode('HWP Document File')); header[35] = 5; new DataView(header.buffer).setUint32(36, flags, true);
  const raw = new Uint8Array(text.length * 2); const view = new DataView(raw.buffer); for (let i = 0; i < text.length; i++) view.setUint16(i * 2, text.charCodeAt(i), true);
  const record = new Uint8Array(raw.length + 8); const rv = new DataView(record.buffer); rv.setUint32(0, 67 | (4095 << 20), true); rv.setUint32(4, raw.length, true); record.set(raw, 8);
  CFB.utils.cfb_add(ole, 'FileHeader', header); CFB.utils.cfb_add(ole, 'BodyText/Section0', flags & 1 ? deflateRaw(record) : record);
  return new Uint8Array(CFB.write(ole, { type: 'array' }));
}

export function pdfFixture(count, blankPages = []) {
  const objects = []; const add = body => { objects.push(body); return objects.length; };
  add('<< /Type /Catalog /Pages 2 0 R >>');
  add(`<< /Type /Pages /Count ${count} /Kids [${Array.from({length:count},(_,i)=>`${4+i*2} 0 R`).join(' ')}] >>`);
  add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
  for (let i=0;i<count;i++) {
    add(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents ${5+i*2} 0 R >>`);
    const content=blankPages.includes(i+1)?'':`BT /F1 12 Tf 40 700 Td (Page ${i+1}: fee 33,000 KRW. Please check conditions.) Tj ET`;
    add(`<< /Length ${content.length} >>\nstream\n${content}\nendstream`);
  }
  let output='%PDF-1.4\n'; const offsets=[0];
  for(let i=0;i<objects.length;i++){offsets.push(output.length);output+=`${i+1} 0 obj\n${objects[i]}\nendobj\n`;}
  const xref=output.length; output+=`xref\n0 ${objects.length+1}\n0000000000 65535 f \n`;
  for(const offset of offsets.slice(1)) output+=`${String(offset).padStart(10,'0')} 00000 n \n`;
  return Buffer.from(output+`trailer\n<< /Size ${objects.length+1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`);
}
