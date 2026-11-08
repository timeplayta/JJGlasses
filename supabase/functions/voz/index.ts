import "jsr:@supabase/functions-js/edge-runtime.d.ts";

// Voz humana do JJGlasses: recebe o texto da resposta da IA e devolve a fala em MP3.
// A chave da OpenAI fica guardada como segredo no Supabase (OPENAI_API_KEY), nunca no app.
// Só aceita pedidos de quem está logado no app.

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });

const VOICES = new Set(["alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar"]);

// Login já conferido fica guardado por 5 min: o app pede a voz em vários pedaços seguidos
// e não precisa conferir o login de novo a cada pedaço (deixa a voz começar mais rápido).
const okTokens = new Map<string, number>();
async function loggedIn(req: Request): Promise<boolean> {
  const auth = req.headers.get("authorization") ?? "";
  const url = Deno.env.get("SUPABASE_URL");
  const anon = Deno.env.get("SUPABASE_ANON_KEY") ?? req.headers.get("apikey") ?? "";
  if (!auth.startsWith("Bearer ") || !url) return false;
  const until = okTokens.get(auth);
  if (until && until > Date.now()) return true;
  const res = await fetch(`${url}/auth/v1/user`, { headers: { Authorization: auth, apikey: anon } });
  if (res.ok) { okTokens.set(auth, Date.now() + 5 * 60 * 1000); if (okTokens.size > 500) okTokens.clear(); }
  return res.ok;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "use_post" }, 405);
  if (!(await loggedIn(req))) return json({ error: "precisa_login" }, 401);
  const key = Deno.env.get("OPENAI_API_KEY");
  if (!key) return json({ error: "sem_chave" }, 503);

  let body: { text?: string; voice?: string; style?: string; pitch?: number; speed?: number; lang?: string; format?: string };
  try { body = await req.json(); } catch { return json({ error: "json_invalido" }, 400); }
  const text = String(body.text ?? "").trim().slice(0, 1500);
  if (!text) return json({ error: "texto_vazio" }, 400);
  const voice = VOICES.has(String(body.voice)) ? String(body.voice) : "coral";
  const style = String(body.style ?? "").slice(0, 200);
  const pitch = Math.max(-2, Math.min(2, Number(body.pitch ?? 0) || 0));
  const speed = Math.max(0.8, Math.min(1.25, Number(body.speed ?? 1) || 1));
  const lang = String(body.lang ?? "Portuguese (Brazil)").slice(0, 40);
  const tone = pitch <= -2 ? "Voz bem mais grave que o normal." : pitch === -1 ? "Voz um pouco mais grave." : pitch === 1 ? "Voz um pouco mais aguda." : pitch >= 2 ? "Voz bem mais aguda que o normal." : "";
  const pace = speed < 0.95 ? "Fale mais devagar." : speed > 1.05 ? "Fale mais rápido." : "";
  const instructions = `Fale em ${lang}, com sotaque nativo natural. Soe como uma pessoa real conversando com um amigo, não como alguém lendo um texto: tom caloroso e expressivo, ritmo solto, pausas curtas e naturais, entonação que sobe e desce com o sentido da frase. ${style} ${tone} ${pace}`.trim();

  // "pcm" = som cru, sem compactar: é o formato que a OpenAI entrega mais rápido (o app toca enquanto chega)
  const pcm = body.format === "pcm";
  const call = (withSpeed: boolean) => fetch("https://api.openai.com/v1/audio/speech", {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model: "gpt-4o-mini-tts", voice, input: text, instructions, response_format: pcm ? "pcm" : "mp3", ...(withSpeed ? { speed } : {}) }),
  });
  let upstream = await call(speed !== 1);
  if (!upstream.ok && upstream.status === 400 && speed !== 1) upstream = await call(false);
  if (!upstream.ok || !upstream.body) {
    console.error("openai tts", upstream.status, (await upstream.text().catch(() => "")).slice(0, 300));
    return json({ error: "tts_falhou", status: upstream.status }, 502);
  }
  return new Response(upstream.body, { headers: { ...CORS, "Content-Type": pcm ? "audio/pcm;rate=24000" : "audio/mpeg", "Cache-Control": "no-store" } });
});
