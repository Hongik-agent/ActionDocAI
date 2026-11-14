import { build } from 'esbuild';
import { cp, mkdir, rm } from 'node:fs/promises';
await rm('dist', { recursive: true, force: true }); await mkdir('dist');
await cp('public', 'dist', { recursive: true });
await build({ entryPoints: ['src/app.js', 'src/hwp-worker.js'], outdir: 'dist', bundle: true, format: 'esm', platform: 'browser', target: ['es2022'], minify: true, legalComments: 'eof' });
await cp('node_modules/pdfjs-dist/build/pdf.worker.min.mjs', 'dist/pdf.worker.mjs');
await cp('node_modules/pdfjs-dist/LICENSE', 'dist/PDFJS-LICENSE.txt');
await cp('node_modules/cfb/LICENSE', 'dist/CFB-LICENSE.txt');
await cp('node_modules/pako/LICENSE', 'dist/PAKO-LICENSE.txt');
await mkdir('dist/ocr');
await cp('node_modules/tesseract.js/dist/worker.min.js','dist/ocr/worker.min.js');
for (const name of ['tesseract-core-lstm.wasm.js','tesseract-core-simd-lstm.wasm.js','LICENSE'])
  await cp('node_modules/tesseract.js-core/'+name,'dist/ocr/'+name);
for (const lang of ['kor','eng'])
  await cp('node_modules/@tesseract.js-data/'+lang+'/4.0.0_best_int/'+lang+'.traineddata.gz','dist/ocr/'+lang+'.traineddata.gz');
