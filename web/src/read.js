import { createWorker } from 'tesseract.js';
import { getDocument, GlobalWorkerOptions, OPS, Util } from 'pdfjs-dist';
import { LIMITS, blocksFromText, pdfPageText, validateDocument } from './summary.js';
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
const OCR_TIMEOUT = 'ocr-timeout';
// Canvas rectangles of drawn images, following the transforms pdf.js applies while rendering.
export function imageRegions({ fnArray, argsArray }, viewport) {
  const stack = [], regions = []; let ctm = viewport.transform;
  fnArray.forEach((op, i) => {
    const args = argsArray[i];
    if (op === OPS.save || op === OPS.paintFormXObjectBegin) { stack.push(ctm); if (op === OPS.paintFormXObjectBegin && args[0]) ctm = Util.transform(ctm, args[0]); }
    else if (op === OPS.restore || op === OPS.paintFormXObjectEnd) ctm = stack.pop() ?? ctm;
    else if (op === OPS.transform) ctm = Util.transform(ctm, args);
    else if (op === OPS.paintImageXObject || op === OPS.paintInlineImageXObject) {
      const image = op === OPS.paintInlineImageXObject ? args[0] : { width: args[1], height: args[2] };
      const [x1, y1, x2, y2] = Util.getAxialAlignedBoundingBox([0, 0, 1, 1], ctm);
      const left = Math.max(0, Math.floor(x1)), top = Math.max(0, Math.floor(y1));
      const width = Math.min(Math.ceil(viewport.width), Math.ceil(x2)) - left, height = Math.min(Math.ceil(viewport.height), Math.ceil(y2)) - top;
      if (width >= 16 && height >= 16) regions.push({ pixels: image.width * image.height, rectangle: { left, top, width, height } });
    }
  });
  return regions;
}
function limited(promise, signal, ms) {
  return new Promise((resolve, reject) => {
    const abort = () => reject(signal.reason), timer = ms && setTimeout(() => reject(new Error(OCR_TIMEOUT)), ms);
    signal?.addEventListener('abort', abort, { once: true });
    promise.then(resolve, reject).finally(() => { clearTimeout(timer); signal?.removeEventListener('abort', abort); });
  });
}
// Each worker holds about 200MB of OCR models, so phones and low-memory devices get fewer.
function ocrPool() {
  const small = (navigator.deviceMemory || 8) <= 4 || matchMedia('(pointer: coarse)').matches;
  const size = Math.max(1, Math.min((navigator.hardwareConcurrency || 2) - 1, small ? 2 : 4));
  const workers = [], idle = [], waiting = []; let created = 0, closed = false;
  const spawn = () => { created++; return createWorker('kor+eng',1,{workerPath:'/ocr/worker.min.js',corePath:'/ocr',langPath:'/ocr',workerBlobURL:false})
    .then(w => { if (closed) { w.terminate(); throw new Error('OCR이 종료되었습니다.'); } workers.push(w); return w; }); };
  return {
    acquire: () => idle.length ? Promise.resolve(idle.pop()) : created < size ? spawn() : new Promise(r => waiting.push(r)),
    release: w => waiting.length ? waiting.shift()(w) : idle.push(w),
    // A timed-out worker may still be busy, so replace it instead of reusing it.
    discard: w => { created--; workers.splice(workers.indexOf(w), 1); w.terminate(); if (waiting.length) waiting.shift()(spawn()); },
    close: () => { closed = true; return Promise.all(workers.map(w => w.terminate())); }
  };
}
function pageBody({text: pageText, found}) {
  let text = pageText;
  const native = text.replace(/\s/g,'');
  for (const line of (found ?? '').split(/\r?\n/)) {
    if (line.trim() && !native.includes(line.replace(/\s/g,''))) text += '\n' + line;
  }
  return text;
}
export async function readFile(file, progress = () => {}, signal, options = {}) {
  if (file.size > LIMITS.fileBytes) throw new Error('파일은 50MB까지 지원합니다.');
  const ext = file.name.toLowerCase().split('.').pop();
  if (!['pdf', 'hwp', 'html', 'htm', 'txt', 'md'].includes(ext)) throw new Error('PDF·HWP·HTML·TXT·MD 파일을 선택해 주세요. HWPX는 지원하지 않습니다.');
  if (ext === 'pdf') {
    const task = getDocument({ data: new Uint8Array(await file.arrayBuffer()), isEvalSupported: false, stopAtErrors: true });
    const ocr = ocrPool();
    try {
      const pdf = await task.promise;
      const markdown = options.wholeMarkdown;
      const parts = markdown ? ['# PDF 전체 본문\n', options.fast ? '\n이미지 속 글자는 제외했습니다.\n' : '\n이미지 속 글자는 OCR로 인식했습니다. 금액과 기한은 원본을 확인하세요.\n'] : [];
      const start = markdown ? 1 : options.start ?? 1;
      const end = markdown ? pdf.numPages : options.end ?? Math.min(pdf.numPages, start + LIMITS.pages - 1);
      if (!Number.isInteger(start) || !Number.isInteger(end) || start < 1 || end < start || end > pdf.numPages || (!markdown && end - start >= LIMITS.pages)) {
        throw new Error(`전체 ${pdf.numPages}쪽 중 연속된 1~50쪽을 선택해 주세요. 시작 쪽과 끝 쪽을 확인해 주세요.`);
      }
      const pages = [], jobs = []; let characters = 0, done = 0;
      for (let page = start; page <= end; page++) {
        signal?.throwIfAborted(); progress(`PDF 원본 ${page}쪽 (선택 ${start}–${end}쪽) 읽는 중`);
        const source = await pdf.getPage(page); const content = await source.getTextContent();
        const text = pdfPageText(content.items);
        characters += text.length;
        if (!markdown && characters > LIMITS.characters) throw new Error('본문은 160,000자까지 지원합니다. 선택 범위를 줄여 주세요.');
        const entry = { page, text }; pages.push(entry);
        if (!options.fast) {
          const operations = await source.getOperatorList();
          const bounds = source.getViewport({scale:1});
          const scale = Math.min(2.5, 3600 / Math.max(bounds.width,bounds.height), Math.sqrt(8000000/(bounds.width*bounds.height)));
          const viewport = source.getViewport({scale});
          const images = imageRegions(operations, viewport);
          // Scans get a full-page pass; pages with enough native text only read their large images.
          // Under 40 characters is less than a sentence, so the page's content must be in its image layer.
          // Images under 250,000 pixels (about 500×500) are logos and icons rather than text-bearing figures.
          const scanned = text.trim() ? text.replace(/\s/g,'').length < 40 && images.length > 0 : operations.fnArray.length > 0;
          const regions = scanned ? [{}] : images.filter(i => i.pixels >= 250000).map(({rectangle}) => ({rectangle}));
          if (regions.length) {
            progress(`한국어·영어 OCR 준비 · 원본 ${page}쪽 (선택 ${start}–${end}쪽)`);
            // Render only once a worker is free, so at most one canvas per worker is held in memory.
            const worker = await limited(ocr.acquire(), signal);
            const canvas = document.createElement('canvas');
            canvas.width = Math.ceil(viewport.width); canvas.height = Math.ceil(viewport.height);
            try { await source.render({canvasContext:canvas.getContext('2d'),viewport}).promise; }
            catch (error) { ocr.release(worker); canvas.width = canvas.height = 0; throw error; }
            const job = (async () => {
              try {
                let found = '';
                for (const options of regions) found += '\n' + (await limited(worker.recognize(canvas, options), signal, 60000)).data.text;
                ocr.release(worker); entry.found = found;
              } catch (error) {
                ocr.discard(worker);
                if (error.message !== OCR_TIMEOUT) throw error;
                entry.skipped = true;
              } finally { canvas.width = canvas.height = 0; progress(`한국어·영어 OCR · ${++done} / ${jobs.length}쪽 완료`); }
            })();
            job.catch(() => {}); jobs.push(job);
          }
        }
        source.cleanup();
        if (markdown && (pages.length === 10 || page === end)) {
          await limited(Promise.all(jobs), signal);
          for (const item of pages) {
            const body = pageBody(item);
            parts.push(new Blob([`\n## ${item.page}쪽\n\n`,
              item.skipped ? '> OCR 시간 초과 · 이미지 내용을 원본에서 확인하세요.\n\n' : '',
              body.trim() ? body.replace(/^/gm, '    ') : '> 읽을 수 있는 텍스트가 없습니다.',
              '\n']));
          }
          pages.length = jobs.length = done = 0;
        }
        await new Promise(r => setTimeout(r, 0));
      }
      await limited(Promise.all(jobs), signal);
      signal?.throwIfAborted();
      if (markdown) return new Blob(parts, {type: 'text/markdown;charset=utf-8'});
      const blocks = [], recognized = [], skipped = [], empty = [];
      for (const { page, text: pageText, found, skipped: late } of pages) {
        const text = pageBody({text:pageText, found});
        if (found?.trim()) recognized.push(page);
        if (late) skipped.push(page);
        if (!text.trim()) empty.push(page);
        blocks.push(...blocksFromText(text, page, blocks.length));
      }
      const warnings = ['PDF 표의 열 순서와 줄바꿈은 원본과 다를 수 있습니다. 금액·기한·각주를 원본과 대조해 주세요.'];
      if (recognized.length) warnings.push('OCR 인식 쪽: '+recognized.join(', ')+'. 인식한 금액·기한·예외는 원본 이미지와 대조해 주세요.');
      if (skipped.length) warnings.push(`OCR 시간이 초과되어 문자 인식을 건너뛴 쪽: ${skipped.join(', ')}. 이 쪽의 이미지 속 내용은 원본에서 확인해 주세요.`);
      if (empty.length) warnings.push(`텍스트를 읽지 못한 쪽: ${empty.join(', ')}. 빈 쪽 또는 이미지·스캔일 수 있으며 이 쪽의 내용은 결과에 포함되지 않았습니다.`);
      if (start !== 1 || end !== pdf.numPages) warnings.unshift(`전체 ${pdf.numPages}쪽 중 원본 ${start}–${end}쪽만 정리했습니다.`);
      if (options.fast) {
        if (!blocks.length) throw new Error('선택한 범위에 추출 가능한 텍스트가 없습니다. 빠르게 읽기를 해제하고 OCR로 다시 정리해 주세요.');
        warnings.push('빠르게 읽기로 이미지 속 글자는 제외했습니다.');
      }
      const doc = validateDocument({ name: file.name, pages: end - start + 1, blocks, warnings });
      return { ...doc, startPage: start };
    } catch (error) {
      if (error.name === 'PasswordException') throw new Error('암호가 설정된 PDF는 지원하지 않습니다. 암호를 제거해 주세요.');
      throw error;
    } finally { try { await ocr.close(); } finally { await task.destroy(); } }
  }
  progress('문서 본문 읽는 중');
  const text = ext === 'hwp' ? await hwpText(await file.arrayBuffer()) : ['html', 'htm'].includes(ext) ? htmlText(await file.text()) : await file.text();
  return validateDocument({ name: file.name, pages: 0, blocks: blocksFromText(text), warnings: ext === 'hwp' ? ['HWP 5의 본문 텍스트를 추출했습니다. 원본 쪽 번호와 표의 행·열 배치는 보존하지 않습니다. 이미지·수식·도형의 내용은 누락될 수 있습니다.'] : [] });
}
