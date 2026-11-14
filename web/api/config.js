import {geminiKey} from '../server/gemini.js';
export default function config(req,res) {
  const e=process.env;
  res.setHeader('Cache-Control','no-store');
  res.json({enabled:Boolean(e.SUPABASE_URL && e.SUPABASE_ANON_KEY && e.SUPABASE_SERVICE_ROLE_KEY && (geminiKey() || e.OPENAI_API_KEY)),
    provider:geminiKey() ? 'Google Gemini' : 'OpenAI',
    url:e.SUPABASE_URL, publicKey:e.SUPABASE_ANON_KEY});
}
