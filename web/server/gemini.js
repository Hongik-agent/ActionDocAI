export const geminiKey = () => process.env.GEMINI_API_KEY || process.env.actionDoc;
export const defaultGeminiModel = 'gemini-3.1-flash-lite';

export async function askGemini(model, instructions, input, schema, max) {
  const url = 'https://generativelanguage.googleapis.com/v1beta/models/' + encodeURIComponent(model) + ':generateContent';
  const response = await fetch(url, {
    method: 'POST',
    redirect: 'error',
    signal: AbortSignal.timeout(45000),
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': geminiKey() },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: instructions }] },
      contents: [{ role: 'user', parts: [{ text: JSON.stringify(input) }] }],
      generationConfig: { responseMimeType: 'application/json', responseJsonSchema: schema, maxOutputTokens: max + 2048 }
    })
  });
  if (!response.ok) {
    const failure = await response.json().catch(() => ({}));
    const reason = failure.error?.details?.find(detail => detail.reason)?.reason;
    console.error('Gemini request failed', response.status, /^[A-Z_]{1,80}$/.test(reason) ? reason : '');
    throw Error('provider');
  }
  const { candidates } = await response.json();
  const candidate = candidates?.[0];
  if (candidate?.finishReason !== 'STOP') throw Error('incomplete');
  return JSON.parse(candidate.content.parts.filter(p => !p.thought).map(p => p.text).join(''));
}
