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
  if (!response.ok) throw Error('provider');
  const { candidates } = await response.json();
  const candidate = candidates?.[0];
  if (candidate?.finishReason !== 'STOP') throw Error('incomplete');
  return JSON.parse(candidate.content.parts.filter(p => !p.thought).map(p => p.text).join(''));
}
