import { extractHwp } from './hwp.js';
self.onmessage = event => {
  try { self.postMessage({ text: extractHwp(new Uint8Array(event.data)) }); }
  catch (error) { self.postMessage({ error: error.message || 'HWP를 읽지 못했습니다.' }); }
};
