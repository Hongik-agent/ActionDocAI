import { createWorker } from 'tesseract.js';
import { getDocument, GlobalWorkerOptions, OPS } from 'pdfjs-dist';
import { LIMITS, blocksFromText, validateDocument } from './summary.js';
GlobalWorkerOptions.workerSrc = '/pdf.worker.mjs';
export function htmlText(html) {
  // Template contents stay inert: embedded resources are never inserted into the page.
  const template = document.createElement('template'); template.innerHTML = html;
  const doc = template.content;
  doc.querySelectorAll('script,style,noscript,iframe,svg,template,object,embed').forEach(n => n.remove());
  doc.querySelectorAll('br').forEach(n => n.replaceWith('\n'));
  doc.querySelectorAll('p,div,section,article,h1,h2,h3,li,tr').forEach(n => n.prepend('\n'));
  return doc.textContent;
}
function hwpText(buffer) {
  return new Promise((resolve, reject) => {
    const worker = new Worker('/hwp-worker.js', { type: 'module' });
    const timer = setTimeout(() => { worker.terminate(); reject(new Error('HWP 읽기 시간이 초과됐습니다. 문서를 나누어 주세요.')); }, 30000);
    const finish = () => { clearTimeout(timer); worker.terminate(); };
    worker.onmessage = e => { finish(); if (e.data.error) reject(new Error(e.data.error)); else resolve(e.data.text); };
    worker.onerror = () => { finish(); reject(new Error('HWP 처리 중 오류가 발생했습니다. PDF·TXT로 변환해 주세요.')); };
    worker.postMessage(buffer, [buffer]);
  });
}
export async function readFile(file, progress = () => {}) {
  if (file.size > LIMITS.fileBytes) throw new Error('파일은 50MB까지 지원합니다.');
  const ext = file.name.toLowerCase().split('.').pop();
  if (!['pdf', 'hwp', 'html', 'htm', 'txt', 'md'].includes(ext)) throw new Error('PDF·HWP·HTML·TXT·MD 파일을 선택해 주세요. HWPX는 지원하지 않습니다.');
  if (ext === 'pdf') {
    const task = getDocument({ data: new Uint8Array(await file.arrayBuffer()), isEvalSupported: false, stopAtErrors: true });
    let worker;
    const recognized = [];
    try {
      const pdf = await task.promise;
      if (pdf.numPages > LIMITS.pages) throw new Error(`이 PDF는 ${pdf.numPages}쪽입니다. 최대 50쪽까지 지원하므로 필요한 부분을 별도 PDF로 저장해 주세요.`);
      const blocks = [], empty = [];
      for (let page = 1; page <= pdf.numPages; page++) {
        progress(`PDF ${page} / ${pdf.numPages}쪽 읽는 중`);
        const source = await pdf.getPage(page); const content = await source.getTextContent();
        let text = ''; let lastY;
        for (const item of content.items) {
          if (!('str' in item)) continue;
          const y = item.transform[5];
          if (lastY !== undefined && Math.abs(y - lastY) > 3) text += '\n';
          text += item.str + (item.hasEOL ? '\n' : ' '); lastY = y;
        }
        const operations = await source.getOperatorList();
        const imagePage = operations.fnArray.some((op, index) =>
          [OPS.paintImageXObject, OPS.paintInlineImageXObject].includes(op) &&
          (operations.argsArray[index][1] * operations.argsArray[index][2] >= 250000 || text.trim().length < 40));
        if (imagePage || (!text.trim() && operations.fnArray.length)) {
          progress(`한국어·영어 OCR · ${page} / ${pdf.numPages}쪽`);
          const bounds = source.getViewport({scale:1});
          const scale = Math.min(2.5, 3600 / Math.max(bounds.width,bounds.height), Math.sqrt(8000000/(bounds.width*bounds.height)));
          const viewport = source.getViewport({scale});
          const canvas = document.createElement('canvas');
          canvas.width = Math.ceil(viewport.width); canvas.height = Math.ceil(viewport.height);
          try {
            await source.render({canvasContext:canvas.getContext('2d'),viewport}).promise;
            worker ??= await createWorker('kor+eng',1,{workerPath:'/ocr/worker.min.js',corePath:'/ocr',langPath:'/ocr',workerBlobURL:false});
            const result = await worker.recognize(canvas);
            const native = text.replace(/\s/g,'');
            for (const line of result.data.text.split(/\r?\n/)) {
              if (line.trim() && !native.includes(line.replace(/\s/g,''))) text += '\n' + line;
            }
            if (result.data.text.trim()) recognized.push(page);
          } finally { canvas.width = canvas.height = 0; }
        }
        if (!text.trim()) empty.push(page);
        blocks.push(...blocksFromText(text, page, blocks.length)); source.cleanup();
        if (blocks.reduce((n, b) => n + b.text.length, 0) > LIMITS.characters) throw new Error('본문은 160,000자까지 지원합니다.');
        await new Promise(r => setTimeout(r, 0));
      }
      const warnings = ['PDF 표의 열 순서와 줄바꿈은 원본과 다를 수 있습니다. 금액·기한·각주를 원본과 대조해 주세요.'];
      if (recognized.length) warnings.push('OCR 인식 쪽: '+recognized.join(', ')+'. 인식한 금액·기한·예외는 원본 이미지와 대조해 주세요.');
      if (empty.length) warnings.push(`텍스트를 읽지 못한 쪽: ${empty.join(', ')}. 빈 쪽 또는 이미지·스캔일 수 있으며 이 쪽의 내용은 결과에 포함되지 않았습니다.`);
      return validateDocument({ name: file.name, pages: pdf.numPages, blocks, warnings });
    } catch (error) {
      if (error.name === 'PasswordException') throw new Error('암호가 설정된 PDF는 지원하지 않습니다. 암호를 제거해 주세요.');
      throw error;
    } finally { try { await worker?.terminate(); } finally { await task.destroy(); } }
  }
  progress('문서 본문 읽는 중');
  const text = ext === 'hwp' ? await hwpText(await file.arrayBuffer()) : ['html', 'htm'].includes(ext) ? htmlText(await file.text()) : await file.text();
  return validateDocument({ name: file.name, pages: 0, blocks: blocksFromText(text), warnings: ext === 'hwp' ? ['HWP 5의 본문 텍스트를 추출했습니다. 원본 쪽 번호와 표의 행·열 배치는 보존하지 않습니다. 이미지·수식·도형의 내용은 누락될 수 있습니다.'] : [] });
}
