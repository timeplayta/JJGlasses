import "jsr:@supabase/functions-js/edge-runtime.d.ts";

// Cérebro do JJGlasses: recebe a conversa do app, pergunta para a Kimi e devolve a resposta.
// A chave da Kimi fica guardada como segredo no Supabase (MOONSHOT_API_KEY), nunca no app.
// Só responde para quem está logado no app (confere o token de login).

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });

const ENDPOINT = "https://api.moonshot.ai/v1/chat/completions";

const SYSTEM = `Você conversa no idioma da pessoa, natural e útil, no estilo do ChatGPT. Se o idioma não vier, use português do Brasil.

Como escrever:
- Use emoji quando ajudar a leitura, em listas, comparações, humor leve e no clima da resposta. Não coloque emoji em toda frase.
- Se a resposta tiver vários pontos, faça uma lista com "- " ou com números.
- Destaque só o que importa com **negrito**.
- Pergunta simples: resposta curta. Pedido de lista, comparação ou explicação: pode alongar.

Opinião:
- Se pedirem sua opinião, dê uma opinião clara, o motivo e um porém honesto.

Comparação:
- Se pedirem o que é melhor, feche com um vencedor no geral e diga pra quem a outra opção ainda vale mais.
- Se a comparação tiver características, coloque os lados numa tabela markdown. A primeira coluna é o critério. Antes da tabela, um título curto em negrito. Depois, a conclusão.

Fotos que o usuário manda:
- Descreva o que aparece (objetos, texto visível, cores, cena) e responda a pergunta.

Fotos que você cria:
- Se pedirem para criar, desenhar ou gerar uma imagem, escreva uma legenda curta e termine com uma linha neste formato exato:
[[foto: descrição detalhada em inglês, no estilo que pediram, sem nome de pessoa real]]
- Se pedirem foto sexual de criança ou adolescente, recuse em uma frase e não use o marcador.

Não diga seu nome, não cite modelo e não diga quem te criou.`;

const LIVE = `Você está numa LIGAÇÃO DE VOZ. Sua resposta vai ser falada em voz alta.
- Responda curto e natural, como numa conversa por telefone: 1 a 3 frases.
- Nada de listas, tabelas, emoji, markdown ou links.
- Se tiver imagem da câmera, diga o que vê de forma direta.`;

const TOOLS = [
  {
    type: "function",
    function: {
      name: "buscar_na_internet",
      description: "Pesquisa na internet agora. Use para notícias, preços, resultados, horários, endereços e qualquer coisa que muda com o tempo.",
      parameters: { type: "object", properties: { consulta: { type: "string", description: "O que pesquisar" } }, required: ["consulta"] },
    },
  },
  {
    type: "function",
    function: {
      name: "clima",
      description: "Clima agora e previsão dos próximos dias. Sem cidade, usa onde a pessoa está.",
      parameters: { type: "object", properties: { cidade: { type: "string", description: "Cidade, opcional" } } },
    },
  },
];

type Turn = { role: "user" | "assistant"; text: string; image?: string };
type Body = { turns?: Turn[]; name?: string; live?: boolean; lang?: string; place?: { lat: number; lon: number; label?: string } };

async function userFromToken(req: Request): Promise<boolean> {
  const auth = req.headers.get("authorization") ?? "";
  const url = Deno.env.get("SUPABASE_URL");
  const anon = Deno.env.get("SUPABASE_ANON_KEY") ?? req.headers.get("apikey") ?? "";
  if (!auth.startsWith("Bearer ") || !url) return false;
  const res = await fetch(`${url}/auth/v1/user`, { headers: { Authorization: auth, apikey: anon } });
  return res.ok;
}

async function webSearch(key: string, query: string): Promise<string> {
  if (!query) return "Consulta vazia.";
  const res = await fetch("https://api.moonshot.ai/v1/tools/search", {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ text_query: query, limit: 5, timeout_seconds: 20, include_content: false }),
  });
  if (!res.ok) return `A busca falhou (HTTP ${res.status}).`;
  const data = await res.json();
  const find = (o: Record<string, unknown>): unknown[] | null => {
    for (const k of ["results", "data", "items", "search_results", "web_results"]) {
      const v = o?.[k];
      if (Array.isArray(v)) return v;
      if (v && typeof v === "object") {
        const inner = find(v as Record<string, unknown>);
        if (inner) return inner;
      }
    }
    return null;
  };
  const items = find(data) ?? [];
  if (!items.length) return JSON.stringify(data).slice(0, 6000);
  return items.slice(0, 6).map((raw) => {
    const it = raw as Record<string, unknown>;
    const chunks = Array.isArray(it.chunks) ? (it.chunks as Record<string, unknown>[]).slice(0, 2).map((c) => String(c.text ?? "").slice(0, 500)).join(" ") : "";
    return `- ${it.title ?? ""}\n  ${String(it.snippet ?? it.summary ?? "").slice(0, 700)} ${chunks}\n  ${it.date ? "data: " + it.date + "\n  " : ""}${it.url ?? ""}`;
  }).join("\n");
}

async function weather(city: string, place?: Body["place"]): Promise<string> {
  let lat = place?.lat, lon = place?.lon, label = place?.label ?? "";
  if (city) {
    const geo = await (await fetch(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(city)}&count=1&language=pt&format=json`)).json();
    const f = geo?.results?.[0];
    if (!f) return `Não achei a cidade ${city}.`;
    lat = f.latitude; lon = f.longitude;
    label = [f.name, f.admin1, f.country].filter(Boolean).join(", ");
  }
  if (lat == null || lon == null) return "Não sei onde a pessoa está. Pergunte a cidade.";
  const url = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m" +
    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3";
  const body = await (await fetch(url)).text();
  return `Local: ${label}\n${body.slice(0, 4000)}\n(weather_code WMO: 0 céu limpo, 1-3 nuvens, 45/48 neblina, 51-67 chuva, 71-77 neve, 80-82 pancadas, 95-99 trovoadas)`;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "use_post" }, 405);
  if (!(await userFromToken(req))) return json({ error: "precisa_login" }, 401);
  const key = Deno.env.get("MOONSHOT_API_KEY");
  if (!key) return json({ error: "sem_chave" }, 503);

  let body: Body;
  try { body = await req.json(); } catch { return json({ error: "json_invalido" }, 400); }
  const turns = (body.turns ?? []).slice(-24);
  if (!turns.length) return json({ error: "sem_mensagem" }, 400);

  const now = new Date().toLocaleString("pt-BR", { timeZone: "America/Sao_Paulo", dateStyle: "full", timeStyle: "short" });
  let extra = `\n\nAgora é ${now} (horário de Brasília).`;
  if (body.place?.label) extra += ` A pessoa está em ${body.place.label}.`;
  extra += " Você TEM acesso à internet pelas ferramentas buscar_na_internet e clima. Use-as para clima, notícias, preços e tudo que muda com o tempo. Nunca diga que não tem acesso à internet.";
  if (body.live) extra += "\n\n" + LIVE;
  if (body.name) extra += `\n\nPessoa: ${String(body.name).slice(0, 60)}.`;
  if (body.lang) extra += `\n\nIdioma escolhido pela pessoa: ${String(body.lang).slice(0, 40)}. Responda nesse idioma, a não ser que ela escreva em outro.`;

  const pictured = turns.map((t, i) => (t.image ? i : -1)).filter((i) => i >= 0).slice(-2);
  const seeing = pictured.length > 0;
  const messages: Record<string, unknown>[] = [{ role: "system", content: SYSTEM + extra }];
  turns.forEach((t, i) => {
    if (t.role === "user" && t.image && pictured.includes(i)) {
      messages.push({ role: "user", content: [{ type: "text", text: t.text || "O que tem nessa foto?" }, { type: "image_url", image_url: { url: t.image } }] });
    } else {
      messages.push({ role: t.role, content: String(t.text ?? "").slice(0, 12000) });
    }
  });

  const models = seeing ? ["kimi-k3.1-pro", "kimi-k3"] : ["kimi-k3.1-pro-highspeed", "kimi-k3.1-pro", "kimi-k3"];
  let modelIndex = 0, useTools = true;
  for (let round = 0; round < 7; round++) {
    const payload: Record<string, unknown> = {
      model: models[modelIndex],
      reasoning_effort: body.live ? "low" : seeing ? "high" : "low",
      messages,
    };
    if (useTools) payload.tools = TOOLS;
    const res = await fetch(ENDPOINT, { method: "POST", headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" }, body: JSON.stringify(payload) });
    if (!res.ok) {
      console.error("kimi", res.status, (await res.text()).slice(0, 300));
      if (res.status >= 400 && res.status < 500 && modelIndex < models.length - 1) { modelIndex++; continue; }
      if (res.status >= 400 && res.status < 500 && useTools) { useTools = false; continue; }
      return json({ error: "ia_falhou", status: res.status }, 502);
    }
    const data = await res.json();
    const msg = data?.choices?.[0]?.message;
    const calls = msg?.tool_calls ?? [];
    if (calls.length) {
      messages.push(msg);
      for (const call of calls) {
        const name = call?.function?.name;
        let args: Record<string, string> = {};
        try { args = JSON.parse(call?.function?.arguments ?? "{}"); } catch { /* sem argumentos */ }
        let result = "Ferramenta desconhecida.";
        try {
          if (name === "buscar_na_internet") result = await webSearch(key, String(args.consulta ?? ""));
          if (name === "clima") result = await weather(String(args.cidade ?? "").trim(), body.place);
        } catch (e) { result = `Erro ao usar ${name}: ${e}`; }
        messages.push({ role: "tool", tool_call_id: call.id, name, content: result.slice(0, 12000) });
      }
      continue;
    }
    const text = String(msg?.content ?? "").trim();
    return json({ text: text || "Não consegui responder agora. Tenta de novo." });
  }
  return json({ text: "Não consegui responder agora. Tenta de novo." });
});
