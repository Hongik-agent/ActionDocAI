import {setTimeout as delay} from 'node:timers/promises';
export const geminiKey = () => process.env.GEMINI_API_KEY || process.env.actionDoc;
export const defaultGeminiModel = 'gemini-3.1-flash-lite';

// stage names the call in logs; deadline is the epoch ms by which the whole request must finish.
export async function askGemini(model, instructions, input, schema, max, stage, deadline) {
  const url = 'https://generativelanguage.googleapis.com/v1beta/models/' + encodeURIComponent(model) + ':generateContent';
  // The retry waits inside the same deadline, so it never extends the request.
  const started=Date.now(), signal=AbortSignal.timeout(Math.max(1, deadline-started));
  const options={method:'POST',redirect:'error',signal,
    headers:{'Content-Type':'application/json','x-goog-api-key':geminiKey()},
    body:JSON.stringify({systemInstruction:{parts:[{text:instructions}]},
      contents:[{role:'user',parts:[{text:JSON.stringify(input)}]}],
      generationConfig:{responseMimeType:'application/json',responseJsonSchema:schema,maxOutputTokens:max+2048}})};
  try {
    for(let attempt=0;attempt<2;attempt++) {
      const response=await fetch(url,options);
      if(!response.ok) {
        const transient=[500,502,503,504].includes(response.status);
        // Do not log provider response bodies: they can contain source text or credentials.
        console.error('Gemini request failed',response.status,'attempt',attempt+1);
        await response.body?.cancel();
        if(transient && attempt===0){await delay(1000+Math.floor(Math.random()*500),undefined,{signal});continue;}
        throw Error(response.status===429?'provider_limit':transient?'provider_busy':
          [400,401,402,403,404].includes(response.status)?'provider_configuration':'provider');
      }
      const {candidates,usageMetadata:usage}=await response.json();
      // Timing and token counts only, to tell provider queueing from model thinking; never content.
      console.info('Gemini call',JSON.stringify({stage,ms:Date.now()-started,attempts:attempt+1,prompt:usage?.promptTokenCount,thoughts:usage?.thoughtsTokenCount,output:usage?.candidatesTokenCount}));
      const candidate=candidates?.[0];
      if(candidate?.finishReason!=='STOP')throw Error('incomplete');
      try{return JSON.parse(candidate.content.parts.filter(p=>!p.thought).map(p=>p.text).join(''));}
      catch{throw Error('incomplete');}
    }
  } catch(error) {
    if(signal.aborted){console.error('Gemini call timed out',JSON.stringify({stage,ms:Date.now()-started}));throw Error('provider_timeout');}
    if(error instanceof TypeError)throw Error('provider_connection');
    throw error;
  }
}
