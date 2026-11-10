import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
const root = resolve('dist');
const config=JSON.parse(await readFile('vercel.json','utf8'));
const security=Object.fromEntries(config.headers[0].headers.map(h=>[h.key,h.value]));
const types = { '.html': 'text/html;charset=utf-8', '.css': 'text/css;charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.txt': 'text/plain;charset=utf-8' };
createServer(async (req, res) => {
  const pathname = new URL(req.url, 'http://localhost').pathname;
  const path = resolve(root, '.' + decodeURIComponent(pathname === '/' ? '/index.html' : pathname));
  if (!path.startsWith(root + '/')) { res.writeHead(403); res.end(); return; }
  try { const content = await readFile(path); res.writeHead(200, { ...security, 'Content-Type': types[extname(path)] || 'application/octet-stream' }); res.end(content); }
  catch { res.writeHead(404); res.end('Not found'); }
}).listen(Number(process.env.PORT || 4173), '127.0.0.1', () => console.log('ActionDoc Web: http://127.0.0.1:4173'));
