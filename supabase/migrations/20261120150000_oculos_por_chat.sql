-- Óculos de cada chat + cadastro de óculos com "capacidades" (o que cada óculos consegue fazer).
-- Para adicionar um óculos novo no futuro, é só inserir uma linha em oculos_modelos (sem mexer no código do app).

-- cada conversa guarda qual óculos a pessoa escolheu para ela
alter table public.conversas add column if not exists oculos text;

create table if not exists public.oculos_modelos (
  id text primary key,                          -- código curto (ex.: 'ivue')
  nome text not null,                           -- nome que aparece no app
  icone text not null default '👓',
  descricao text not null default '',
  tem_ia boolean not null default false,        -- tem IA própria (responde sozinho)?
  tem_alto_falante boolean not null default false,
  tem_microfone boolean not null default false,
  tem_camera boolean not null default false,
  grava boolean not null default false,         -- grava vídeo?
  resolucoes text[] not null default '{}',      -- ex.: {1080p,2K}
  ao_vivo text not null default '',             -- como manda imagem ao vivo: bluetooth, usb, wifi... ('' = ainda não sabemos)
  ordem int not null default 100,               -- ordem na lista
  ativo boolean not null default true,
  updated_at timestamptz not null default now()
);

-- RLS (segurança por linha): quem está logado só pode LER a lista; mudar só pelo painel do Supabase
alter table public.oculos_modelos enable row level security;
drop policy if exists "oculos_modelos: logado le" on public.oculos_modelos;
create policy "oculos_modelos: logado le" on public.oculos_modelos for select to authenticated using (true);
grant select on public.oculos_modelos to authenticated;

insert into public.oculos_modelos (id, nome, icone, descricao, tem_ia, tem_alto_falante, tem_microfone, tem_camera, grava, resolucoes, ao_vivo, ordem) values
  ('jj',     'JJGlasses W AI 4', '🕶️', 'Com IA, câmera, microfone e alto-falante', true,  true,  true,  true,  true,  '{1080p}',      'bluetooth', 10),
  ('ivue',   'iVUE',             '🎥', 'Câmera e microfone: grava em 1080p e 2K. Sem IA e sem alto-falante', false, false, true, true, true, '{1080p,2K}', '', 20),
  ('nenhum', 'Sem óculos',       '📱', 'Só o celular', false, false, false, false, false, '{}', '', 90)
on conflict (id) do nothing;
