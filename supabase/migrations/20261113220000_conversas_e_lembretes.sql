-- Conversas salvas na nuvem e lembretes/alarmes do JJGlasses.
-- Cada pessoa só vê e mexe nas próprias linhas (RLS = "segurança por linha").
create table if not exists public.conversas (
  id text not null,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title text not null default 'Novo chat',
  at bigint not null default 0,
  lang text,
  pinned boolean not null default false,
  msgs jsonb not null default '[]'::jsonb,
  deleted boolean not null default false,
  updated_at timestamptz not null default now(),
  primary key (user_id, id)
);
create index if not exists conversas_user_updated on public.conversas (user_id, updated_at desc);
alter table public.conversas enable row level security;
create policy "conversas: dono le" on public.conversas for select to authenticated using ((select auth.uid()) = user_id);
create policy "conversas: dono cria" on public.conversas for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "conversas: dono altera" on public.conversas for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "conversas: dono apaga" on public.conversas for delete to authenticated using ((select auth.uid()) = user_id);

create table if not exists public.lembretes (
  id text not null,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  quando timestamptz not null,
  texto text not null,
  tipo text not null default 'lembrete' check (tipo in ('lembrete','alarme')),
  repetir text not null default 'nao' check (repetir in ('nao','diario','semanal')),
  feito boolean not null default false,
  deleted boolean not null default false,
  updated_at timestamptz not null default now(),
  primary key (user_id, id)
);
create index if not exists lembretes_user_quando on public.lembretes (user_id, quando);
alter table public.lembretes enable row level security;
create policy "lembretes: dono le" on public.lembretes for select to authenticated using ((select auth.uid()) = user_id);
create policy "lembretes: dono cria" on public.lembretes for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "lembretes: dono altera" on public.lembretes for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "lembretes: dono apaga" on public.lembretes for delete to authenticated using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.conversas, public.lembretes to authenticated;
