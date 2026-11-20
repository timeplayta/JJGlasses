-- Galeria do óculos: lista das fotos, vídeos originais e vídeos com IA de cada pessoa.
-- Aqui fica só a "ficha" de cada arquivo (data, óculos, contexto, duração, onde está guardado).
-- O arquivo em si vai para o armazenamento (storage) que for escolhido.
create table if not exists public.midias (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  tipo text not null check (tipo in ('foto','video','video_ia')),
  titulo text not null default '',
  oculos text,                                   -- código do óculos (ex.: 'ivue')
  contexto text,                                 -- contexto de uso (paraquedismo, trabalho pesado...)
  resolucao text,                                -- 1080p, 2K...
  duracao_s integer,                             -- duração do vídeo em segundos
  arquivo text,                                  -- onde o arquivo está guardado
  miniatura text,                                -- imagem pequena para a lista
  original_id uuid references public.midias(id) on delete set null,  -- o vídeo com IA aponta para o original
  status text not null default 'pronto' check (status in ('enviando','processando','pronto','erro')),
  progresso integer not null default 100,        -- 0 a 100 (envio ou análise)
  resumo text,                                   -- resumo da IA
  eventos jsonb not null default '[]'::jsonb,    -- momentos importantes encontrados pela IA
  gravado_em timestamptz not null default now(),
  criado_em timestamptz not null default now()
);
create index if not exists midias_user_tipo on public.midias (user_id, tipo, gravado_em desc);

-- RLS (segurança por linha): cada pessoa só vê e mexe nas próprias fotos e vídeos
alter table public.midias enable row level security;
drop policy if exists "midias: dono le" on public.midias;
drop policy if exists "midias: dono cria" on public.midias;
drop policy if exists "midias: dono altera" on public.midias;
drop policy if exists "midias: dono apaga" on public.midias;
create policy "midias: dono le" on public.midias for select to authenticated using ((select auth.uid()) = user_id);
create policy "midias: dono cria" on public.midias for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "midias: dono altera" on public.midias for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "midias: dono apaga" on public.midias for delete to authenticated using ((select auth.uid()) = user_id);
grant select, insert, update, delete on public.midias to authenticated;
