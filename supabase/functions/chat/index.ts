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

Dinheiro e números:
- Entenda valores do jeito que o brasileiro fala: "conto", "pila", "pau", "mangos" = reais; "50 conto" = R$ 50; "2 mil" = R$ 2.000; "1k" = mil; "dez pila" = R$ 10; "meio milhão" = R$ 500.000; Pix, boleto, parcela, juros, troco, desconto.
- Se a frase veio de voz e o valor parecer estranho (ex.: "cinco zero reais"), entenda o mais provável (R$ 50) e confirme rapidinho se fizer diferença.
- Faça as contas com cuidado e mostre o resultado em R$ com centavos quando precisar (ex.: R$ 1.250,90).

Gírias e abreviações de internet:
- Entenda o jeito que o brasileiro escreve no WhatsApp, sem pedir explicação. Exemplos: plmds/plmdd = pelo amor de Deus; mds/meudeus = meu Deus; pfv/pf/pls = por favor; vc/vcs = você(s); tb/tbm = também; q/oq/pq/qnd/qm/cmg/ctg = que/o que/por que/quando/quem/comigo/contigo; n/ñ/nn = não; s/ss/sim sim = sim; blz = beleza; sdds = saudades; obg/vlw = obrigado/valeu; flw = falou (tchau); tmj = tamo junto; pdp = pode pá (pode deixar); slk = sai louco (nossa, impressionante); mt/mto = muito; msm = mesmo; nd/ngm/td/hj/amn/dps/agr = nada/ninguém/tudo/hoje/amanhã/depois/agora; ctz = certeza; vdd = verdade; rlx = relaxa; sqn = só que não; pprt = papo reto (sério); tlgd = tá ligado; mn/mano/mana = amigo(a); kkk/rsrs/hahaha = risada; fds = fim de semana (ou palavrão, pelo contexto); krl/pqp/vsf = palavrões de surpresa ou raiva; bjs = beijos; dnv = de novo; tô/tá/pra/num/cê = estou/está/para/não/você.
- Também entenda palavras sem acento, letras trocadas, erros de digitação e palavras grudadas (ex.: "oque", "concerteza", "derrepente", "agente" = a gente).
- Se uma abreviação puder ter dois sentidos, escolha pelo contexto. Só pergunte se mudar a resposta.
- Responda normalmente, sem corrigir a escrita da pessoa e sem explicar a gíria (a não ser que ela pergunte o que significa).

Fontes (quando pesquisar na internet ou ver o clima):
- Cada resultado da pesquisa vem marcado com [fonte N]. Logo no FIM do parágrafo, do item da lista ou da linha que usou aquela informação, escreva [[fonte:N]] (se forem duas, [[fonte:1,3]]). Assim cada parte da resposta fica com a sua própria fonte embaixo dela.
- Não escreva links, endereços de site nem uma lista de fontes no fim da resposta: o app mostra as fontes sozinho.
- Só marque a fonte que realmente tem aquela informação. Parte que não veio da pesquisa fica sem marca.

Não diga seu nome, não cite modelo e não diga quem te criou.

const LIVE = `Você está numa LIGAÇÃO DE VOZ. Sua resposta vai ser falada em voz alta, como um amigo no telefone.
- Fale como gente de verdade: frases curtas, do jeito falado do dia a dia (em português: "tá", "pra", "olha", "então"; em outra língua, o jeito falado equivalente dela), sem soar como texto lido.
- Vá direto ao ponto na primeira frase. Nada de "Ótima pergunta" nem "Como posso ajudar".
- 1 a 3 frases. Se o assunto for grande, dê o principal e pergunte se a pessoa quer mais detalhes.
- Reaja ao que a pessoa disse (se ela estiver animada, cansada ou brincando, acompanhe o tom).
- Às vezes termine com uma pergunta curta para a conversa continuar, mas não sempre.
- Nada de listas, tabelas, emoji, markdown ou links. Números e horários escritos do jeito que se fala.
- Se tiver imagem da câmera, diga o que vê de forma direta, como quem está olhando junto.`;

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
      name: "criar_lembrete",
      description: "Marca um lembrete ou alarme no celular da pessoa. Use quando ela pedir para lembrar de algo, avisar, acordar ou despertar em um horário.",
      parameters: {
        type: "object",
        properties: {
          quando: { type: "string", description: "Data e hora em ISO 8601 com o fuso da pessoa, ex.: 2026-11-14T09:00:00-03:00" },
          texto: { type: "string", description: "O que lembrar, curto" },
          tipo: { type: "string", enum: ["lembrete", "alarme"], description: "alarme quando for para acordar/despertar ou tocar som; senão lembrete" },
          repetir: { type: "string", enum: ["nao", "diario", "semanal"] },
        },
        required: ["quando", "texto"],
      },
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
type Body = { audio?: string; stt?: string; sttCtx?: string; turns?: Turn[]; name?: string; live?: boolean; stream?: boolean; lang?: string; lock?: boolean; tz?: string; task?: string; text?: string; a?: string; b?: string; place?: { lat: number; lon: number; label?: string } };

// Login já conferido fica guardado por 5 min (a resposta começa mais rápido).
const okTokens = new Map<string, number>();
async function userFromToken(req: Request): Promise<boolean> {
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

type Fonte = { t: string; u: string };
// guarda os sites pesquisados (vira o botão "Fontes" embaixo da resposta no app)
// devolve o número da fonte (1, 2, 3...) para a IA marcar [[fonte:N]] no pedaço da resposta que usou ela
function addFonte(list: Fonte[] | undefined, t: unknown, u: unknown): number {
  const url = String(u ?? "");
  if (!list || !/^https?:\/\//.test(url)) return 0;
  const i = list.findIndex((f) => f.u === url);
  if (i >= 0) return i + 1;
  if (list.length >= 10) return 0;
  list.push({ t: String(t ?? "").slice(0, 120), u: url.slice(0, 500) });
  return list.length;
}

async function webSearch(key: string, query: string, fontes?: Fonte[]): Promise<string> {
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
    const n = addFonte(fontes, it.title, it.url);
    const chunks = Array.isArray(it.chunks) ? (it.chunks as Record<string, unknown>[]).slice(0, 2).map((c) => String(c.text ?? "").slice(0, 500)).join(" ") : "";
    return `- ${n ? `[fonte ${n}] ` : ""}${it.title ?? ""}\n  ${String(it.snippet ?? it.summary ?? "").slice(0, 700)} ${chunks}\n  ${it.date ? "data: " + it.date + "\n  " : ""}${it.url ?? ""}`;
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

async function runTool(key: string, name: string, rawArgs: string, place?: Body["place"], fontes?: Fonte[]): Promise<string> {
  let args: Record<string, string> = {};
  try { args = JSON.parse(rawArgs || "{}"); } catch { /* sem argumentos */ }
  try {
    if (name === "buscar_na_internet") return await webSearch(key, String(args.consulta ?? ""), fontes);
    if (name === "clima") { const n = addFonte(fontes, "Open-Meteo (previsão do tempo)", "https://open-meteo.com/"); return (n ? `[fonte ${n}] ` : "") + await weather(String(args.cidade ?? "").trim(), place); }
    if (name === "criar_lembrete") return `Pronto: ${args.tipo === "alarme" ? "alarme" : "lembrete"} marcado no celular da pessoa para ${args.quando} (${args.texto}). Confirme em uma frase curta, dizendo o dia e a hora do jeito que se fala.`;
  } catch (e) { return `Erro ao usar ${name}: ${e}`; }
  return "Ferramenta desconhecida.";
}

/* Ouvir a voz da ligação: transcreve o áudio com o modelo mais preciso da OpenAI,
   dando dicas de vocabulário (dinheiro, números) e o assunto da conversa. */
const SILENCE = /^(obrigad[oa][.!]?|legendas? (pela|por).*|tchau[.!]?|\.+|você|thank you[.!]?|thanks for watching.*)$/i;
async function hear(b64: string, lang: string, ctx: string): Promise<string> {
  const okey = Deno.env.get("OPENAI_API_KEY");
  if (!okey || !b64) return "";
  const bin = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  if (bin.byteLength < 8000) return ""; // menos de ~0,25 s de som
  const form = new FormData();
  form.append("file", new Blob([bin], { type: "audio/wav" }), "fala.wav");
  form.append("model", "gpt-4o-transcribe");
  const l = String(lang || "").slice(0, 2).toLowerCase();
  if (/^[a-z]{2}$/.test(l)) form.append("language", l);
  form.append("prompt", `Conversa por voz com uma assistente. Pode ter valores em dinheiro (reais, R$, centavos, Pix, conto, pila, mil, milhão), números, datas e horários: escreva dinheiro como R$ 50, R$ 1.250,90. ${String(ctx || "").slice(0, 300)}`);
  const res = await fetch("https://api.openai.com/v1/audio/transcriptions", { method: "POST", headers: { Authorization: `Bearer ${okey}` }, body: form });
  if (!res.ok) { console.error("ouvir", res.status, (await res.text().catch(() => "")).slice(0, 200)); return ""; }
  const t = String((await res.json())?.text ?? "").trim();
  return SILENCE.test(t) && bin.byteLength < 64000 ? "" : t;
}

type Prep = { messages: Record<string, unknown>[]; models: string[]; effort: string; maxTokens?: number };

/* Resposta em tempo real: manda o texto para o app pedacinho por pedacinho (cada linha é um JSON).
   Assim o app já mostra e já começa a falar a primeira frase enquanto a IA ainda escreve o resto. */
function streamAnswer(key: string, prepare: (out: (o: unknown) => void) => Promise<Prep | null>, place?: Body["place"]): Response {
  const enc = new TextEncoder();
  const stream = new ReadableStream({
    async start(ctrl) {
      const out = (o: unknown) => ctrl.enqueue(enc.encode(JSON.stringify(o) + "\n"));
      try {
        const prep = await prepare(out);
        if (!prep) { out({ done: true }); ctrl.close(); return; }
        const { messages, models, effort, maxTokens } = prep;
        let modelIndex = 0, useTools = true;
        const fontes: Fonte[] = [];
        for (let round = 0; round < 7; round++) {
          const payload: Record<string, unknown> = { model: models[modelIndex], reasoning_effort: effort, messages, stream: true };
          if (maxTokens) payload.max_tokens = maxTokens;
          if (useTools) payload.tools = TOOLS;
          const res = await fetch(ENDPOINT, { method: "POST", headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" }, body: JSON.stringify(payload) });
          if (!res.ok || !res.body) {
            console.error("kimi stream", res.status, (await res.text().catch(() => "")).slice(0, 300));
            if (res.status >= 400 && res.status < 500 && modelIndex < models.length - 1) { modelIndex++; continue; }
            if (res.status >= 400 && res.status < 500 && useTools) { useTools = false; continue; }
            out({ error: "ia_falhou", status: res.status }); ctrl.close(); return;
          }
          const reader = res.body.getReader(), dec = new TextDecoder();
          let buf = "", content = "", reasoning = "";
          const calls: { id: string; type: string; function: { name: string; arguments: string } }[] = [];
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buf += dec.decode(value, { stream: true });
            let k;
            while ((k = buf.indexOf("\n")) >= 0) {
              const line = buf.slice(0, k).trim(); buf = buf.slice(k + 1);
              if (!line.startsWith("data:")) continue;
              const d = line.slice(5).trim();
              if (!d || d === "[DONE]") continue;
              let j; try { j = JSON.parse(d); } catch { continue; }
              const dl = j?.choices?.[0]?.delta ?? {};
              if (dl.reasoning_content) reasoning += dl.reasoning_content;
              if (dl.content) { content += dl.content; out({ d: dl.content }); }
              for (const t of dl.tool_calls ?? []) {
                const x = calls[t.index ?? 0] ??= { id: "", type: "function", function: { name: "", arguments: "" } };
                if (t.id) x.id = t.id;
                if (t.function?.name) x.function.name += t.function.name;
                if (t.function?.arguments) x.function.arguments += t.function.arguments;
              }
            }
          }
          const tc = calls.filter(Boolean);
          if (tc.length) {
            const m: Record<string, unknown> = { role: "assistant", content: content, tool_calls: tc };
            if (reasoning) m.reasoning_content = reasoning;
            messages.push(m);
            out({ tool: tc.map((t) => t.function.name).join(",") });
            for (const call of tc) {
              if (call.function.name === "criar_lembrete") { try { out({ lembrete: JSON.parse(call.function.arguments || "{}") }); } catch { /* sem dados */ } }
              const result = await runTool(key, call.function.name, call.function.arguments, place, fontes);
              messages.push({ role: "tool", tool_call_id: call.id, name: call.function.name, content: result.slice(0, 12000) });
            }
            if (fontes.length) out({ fontes });
            continue;
          }
          if (!content.trim()) out({ d: "Não consegui responder agora. Tenta de novo." });
          out({ done: true }); ctrl.close(); return;
        }
        out({ d: "Não consegui responder agora. Tenta de novo." }); out({ done: true }); ctrl.close();
      } catch (e) { console.error("stream", e); try { out({ error: String(e) }); ctrl.close(); } catch { /* fechado */ } }
    },
  });
  return new Response(stream, { headers: { ...CORS, "Content-Type": "application/x-ndjson", "Cache-Control": "no-store" } });
}

/* Tradutor em tempo real: descobre se a frase foi dita na língua A ou B e traduz para a outra. */
async function translate(key: string, body: Body): Promise<Response> {
  const text = String(body.text ?? "").trim().slice(0, 2000);
  const a = String(body.a ?? "Portuguese (Brazil)").slice(0, 40), b = String(body.b ?? "English").slice(0, 40);
  if (!text) return json({ error: "texto_vazio" }, 400);
  const sys = `Você é um intérprete. Língua A: ${a}. Língua B: ${b}.\nA frase foi falada em A ou em B. Descubra qual e traduza para a OUTRA língua, do jeito natural que um nativo falaria (não palavra por palavra), mantendo o tom. Entenda gírias e abreviações de internet antes de traduzir (ex.: plmds = pelo amor de Deus, vc = você, pfv = por favor, tb = também).\nResponda SOMENTE com JSON: {"de":"a" ou "b","texto":"tradução"}`;
  for (const model of ["kimi-k3.1-pro-highspeed", "kimi-k3.1-pro", "kimi-k3"]) {
    const res = await fetch(ENDPOINT, { method: "POST", headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
      body: JSON.stringify({ model, reasoning_effort: "low", response_format: { type: "json_object" }, messages: [{ role: "system", content: sys }, { role: "user", content: text }] }) });
    if (!res.ok) { console.error("traduzir", model, res.status, (await res.text().catch(() => "")).slice(0, 200)); continue; }
    const data = await res.json();
    const raw = String(data?.choices?.[0]?.message?.content ?? "");
    try {
      const j = JSON.parse(raw.slice(raw.indexOf("{"), raw.lastIndexOf("}") + 1));
      if (j?.texto) return json({ de: j.de === "b" ? "b" : "a", texto: String(j.texto) });
    } catch { /* tenta o próximo */ }
  }
  return json({ error: "traducao_falhou" }, 502);
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "use_post" }, 405);
  if (!(await userFromToken(req))) return json({ error: "precisa_login" }, 401);
  const key = Deno.env.get("MOONSHOT_API_KEY");
  if (!key) return json({ error: "sem_chave" }, 503);

  let body: Body;
  try { body = await req.json(); } catch { return json({ error: "json_invalido" }, 400); }
  if (body.task === "traduzir") return translate(key, body);
  const turns = (body.turns ?? []).slice(-24);
  if (!turns.length) return json({ error: "sem_mensagem" }, 400);
  const build = (): Prep => {

    let tz = "America/Sao_Paulo";
    try { if (body.tz) { new Date().toLocaleString("en-US", { timeZone: body.tz }); tz = body.tz; } } catch { /* fuso inválido */ }
    const now = new Date().toLocaleString("pt-BR", { timeZone: tz, dateStyle: "full", timeStyle: "short" });
    const iso = new Date().toLocaleString("sv-SE", { timeZone: tz }).replace(" ", "T");
    const off = (new Date().toLocaleString("en-US", { timeZone: tz, timeZoneName: "longOffset" }).match(/GMT([+-]\d\d:\d\d)/) ?? [, "+00:00"])[1];
    let extra = `\n\nAgora é ${now} no fuso da pessoa (${tz}, UTC${off}; data e hora local ${iso}${off}). Para lembretes e alarmes, use a ferramenta criar_lembrete com esse fuso.`;
    if (body.place?.label) extra += ` A pessoa está em ${body.place.label}.`;
    extra += " Você TEM acesso à internet pelas ferramentas buscar_na_internet e clima. Use-as para clima, notícias, preços e tudo que muda com o tempo. Nunca diga que não tem acesso à internet.";
    if (body.live) extra += "\n\n" + LIVE;
    if (body.name) extra += `\n\nPessoa: ${String(body.name).slice(0, 60)}.`;
    const lang = String(body.lang ?? "").slice(0, 40);
    // lock = a pessoa escolheu a língua DESTA conversa nos 3 pontinhos: responder sempre nela
    if (lang && body.lock) extra += `\n\nLÍNGUA OBRIGATÓRIA DESTA CONVERSA: ${lang}. Escreva TODAS as respostas somente em ${lang}, mesmo que a pessoa escreva em português ou em outra língua, e mesmo que as mensagens anteriores estejam em outra língua. Não explique isso, apenas responda em ${lang}.`;
    else if (lang) extra += `\n\nIdioma escolhido pela pessoa: ${lang}. Responda nesse idioma, a não ser que ela escreva em outro.`;

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

    // reforço no fim da última mensagem da pessoa (os modelos seguem melhor a última instrução)
    if (lang && body.lock) {
      const last = messages[messages.length - 1];
      const note = `\n\n(Responda somente em ${lang}.)`;
      if (last && last.role === "user") {
        if (typeof last.content === "string") last.content += note;
        else if (Array.isArray(last.content)) (last.content as { type: string; text?: string }[]).forEach((p) => { if (p.type === "text") p.text = (p.text ?? "") + note; });
      }
    }

    const models = seeing ? ["kimi-k3.1-pro", "kimi-k3"] : ["kimi-k3.1-pro-highspeed", "kimi-k3.1-pro", "kimi-k3"];
    const effort = body.live ? "low" : seeing ? "high" : "low";
    return { messages, models, effort, maxTokens: body.live ? 1200 : undefined };
  };
  // ligação: o app manda o áudio junto e o servidor ouve e responde no mesmo pedido (economiza uma viagem)
  if (body.audio && body.stream) {
    return streamAnswer(key, async (out) => {
      const heard = await hear(body.audio!, body.stt ?? "", body.sttCtx ?? "");
      out({ heard });
      if (!heard) return null;
      const last = turns[turns.length - 1];
      if (last && last.role === "user") last.text = heard;
      return build();
    }, body.place);
  }
  const prep = build();
  const { messages, models } = prep;
  const seeing = turns.some((t) => t.image);
  if (body.stream) return streamAnswer(key, async () => prep, body.place);
  let modelIndex = 0, useTools = true;
  const lembretes: Record<string, string>[] = [];
  const fontes: Fonte[] = [];
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
        if (name === "criar_lembrete") lembretes.push(args);
        result = await runTool(key, name, call?.function?.arguments ?? "{}", body.place, fontes);
        messages.push({ role: "tool", tool_call_id: call.id, name, content: result.slice(0, 12000) });
      }
      continue;
    }
    const text = String(msg?.content ?? "").trim();
    return json({ text: text || "Não consegui responder agora. Tenta de novo.", lembretes, fontes });
  }
  return json({ text: "Não consegui responder agora. Tenta de novo." });
});
