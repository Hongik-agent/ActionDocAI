import {defaultGeminiModel, geminiKey} from '../server/gemini.js';
import {createHash} from 'node:crypto';
import {createClient} from '@supabase/supabase-js';
import {verifiedSummary,checkEvidence} from '../server/verify.js';
export const config={maxDuration:120};
const labels={GENERAL:'전체 정리',BUSINESS:'공문·업무 요청',SUPPORT:'지원사업·모집 공고',CONTRACT:'요금·계약 안내'};
export default async function summary(req,res) {
  res.setHeader('Cache-Control','private, no-store');
  if(req.method!=='POST') return res.status(405).end();
  const {blocks,purpose,pages,consent}=req.body??{};
  if(consent!==true || !Object.hasOwn(labels,purpose) || !Number.isInteger(pages) || pages<0 || pages>10 ||
    !Array.isArray(blocks) || !blocks.length || blocks.length>10000 ||
    blocks.some(b=>typeof b?.text!=='string'||!b.text.trim()||!Number.isInteger(b.page)||b.page<0||b.page>pages) ||
    blocks.reduce((n,b)=>n+b.text.length,0)>10000) return res.status(400).json({status:'length'});
  const token=req.headers.authorization?.match(/^Bearer (\S+)$/)?.[1];
  if(!token) return res.status(401).json({status:'login'});
  try {
    const db=createClient(process.env.SUPABASE_URL,process.env.SUPABASE_SERVICE_ROLE_KEY,{auth:{persistSession:false,autoRefreshToken:false}});
    const {data:{user},error}=await db.auth.getUser(token);
    if(error||!user) return res.status(401).json({status:'login'});
    const gemini = Boolean(geminiKey());
    const model = gemini ? process.env.GEMINI_MODEL || defaultGeminiModel : process.env.OPENAI_MODEL || 'gpt-4.1-mini';
    if(!gemini && !process.env.OPENAI_API_KEY) throw Error('configuration');
    const source=blocks.map(({text,page})=>({text,page}));
    const f=createHash('sha256').update(JSON.stringify([1,gemini ? 'gemini' : 'openai',model,purpose,pages,source])).digest('hex');
    const limit=(key,fallback)=>{const n=Number(process.env[key]??fallback);if(!Number.isSafeInteger(n)||n<0||n>100000) throw Error('limit');return n;};
    const reservation=await db.rpc('actiondoc_reserve',{u:user.id,f,personal:limit('USER_DAILY_LIMIT',3),total:limit('GLOBAL_DAILY_LIMIT',30)});
    if(reservation.error) throw Error('quota');
    const slot=reservation.data;
    if(slot.status==='cached') return res.json({status:'cached',items:checkEvidence(slot.result,source)});
    if(slot.status!=='reserved') return res.json({status:['personal','global','busy'].includes(slot.status)?slot.status:'unavailable'});
    const items=await verifiedSummary(source,labels[purpose],model);
    const saved=await db.from('actiondoc_jobs').update({result:items}).eq('job',slot.job).eq('owner',user.id);
    if(saved.error) throw Error('cache');
    return res.json({status:'complete',items});
  } catch { return res.status(503).json({status:'unavailable'}); }
}
