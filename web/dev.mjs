import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
import configApi from './api/config.js';
import summaryApi from './api/summary.js';
const root = resolve('dist');
const config=JSON.parse(await readFile('vercel.json','utf8'));
const security=Object.fromEntries(config.headers[0].headers.map(h=>[h.key,h.value]));
const types = { '.html': 'text/html;charset=utf-8', '.css': 'text/css;charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.txt': 'text/plain;charset=utf-8' };
createServer(async (req, res) => {
  const pathname = new URL(req.url, 'http://localhost').pathname;
  if (pathname === '/api/config' || pathname === '/api/summary') {
    res.status = code => { res.statusCode = code; return res; };
    res.json = data => { res.setHeader('Content-Type','application/json'); res.end(JSON.stringify(data)); };
    if(pathname==='/api/config') return configApi(req,res);
    try {
      let size=0;const chunks=[];
      for await(const chunk of req){size+=chunk.length;if(size>512000)return res.status(413).end();chunks.push(chunk);}
      req.body=JSON.parse(Buffer.concat(chunks).toString('utf8')||'{}');
    }catch{return res.status(400).end();}
    return summaryApi(req,res);
  }
  const path = resolve(root, '.' + decodeURIComponent(pathname === '/' ? '/index.html' : pathname));
  if (!path.startsWith(root + '/')) { res.writeHead(403); res.end(); return; }
  try { const content = await readFile(path); res.writeHead(200, { ...security, 'Content-Type': types[extname(path)] || 'application/octet-stream' }); res.end(content); }
  catch { res.writeHead(404); res.end('Not found'); }
}).listen(Number(process.env.PORT || 4173), '127.0.0.1', () => console.log('ActionDoc Web: http://127.0.0.1:4173'));
