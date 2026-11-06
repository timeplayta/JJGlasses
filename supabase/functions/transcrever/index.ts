import "jsr:@supabase/functions-js/edge-runtime.d.ts";

// Transcrição do JJGlasses: recebe o áudio gravado no app (como áudio de WhatsApp)
// e devolve o texto. Usa a OpenAI (chave guardada no Supabase como OPENAI_API_KEY).

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });

async function loggedIn(req: Request): Promise<boolean> {
  const auth = req.headers.get("authorization") ?? "";
  const url = Deno.env.get("SUPABASE_URL");
  const anon = Deno.env.get("SUPABASE_ANON_KEY") ?? req.headers.get("apikey") ?? "";
  if (!auth.startsWith("Bearer ") || !url) return false;
  const res = await fetch(`${url}/auth/v1/user`, { headers: { Authorization: auth, apikey: anon } });
  return res.ok;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "use_post" }, 405);
  if (!(await loggedIn(req))) return json({ error: "precisa_login" }, 401);
  const key = Deno.env.get("OPENAI_API_KEY");
  if (!key) return json({ error: "sem_chave" }, 503);

  const type = req.headers.get("content-type") ?? "audio/webm";
  const audio = await req.arrayBuffer();
  if (audio.byteLength < 800) return json({ text: "" });
  if (audio.byteLength > 20 * 1024 * 1024) return json({ error: "audio_grande" }, 413);
  const ext = type.includes("mp4") || type.includes("m4a") ? "m4a" : type.includes("ogg") ? "ogg" : type.includes("wav") ? "wav" : "webm";

  const form = new FormData();
  form.append("file", new Blob([audio], { type }), `fala.${ext}`);
  form.append("model", "gpt-4o-mini-transcribe");
  form.append("language", "pt");
  const res = await fetch("https://api.openai.com/v1/audio/transcriptions", {
    method: "POST",
    headers: { Authorization: `Bearer ${key}` },
    body: form,
  });
  if (!res.ok) {
    console.error("transcricao", res.status, (await res.text()).slice(0, 300));
    return json({ error: "transcricao_falhou", status: res.status }, 502);
  }
  const data = await res.json();
  return json({ text: String(data?.text ?? "").trim() });
});
