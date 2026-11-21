-- Gestores (quem vê os vídeos dos colaboradores) e regras de envio para a nuvem.
create table if not exists public.gestores (
  user_id uuid primary key references auth.users(id) on delete cascade,
  nome text not null default '',
  criado_em timestamptz not null default now()
);
create table if not exists public.gestor_colaborador (
  gestor_id uuid not null references public.gestores(user_id) on delete cascade,
  colaborador_id uuid not null references auth.users(id) on delete cascade,
  primary key (gestor_id, colaborador_id)
);
-- Regras de envio de cada colaborador (o gestor configura; o app lê).
create table if not exists public.config_envio (
  user_id uuid primary key references auth.users(id) on delete cascade,
  ativo boolean not null default true,
  intervalo_min integer not null default 60 check (intervalo_min between 15 and 1440),
  so_wifi_com_internet boolean not null default true,
  atualizado_por uuid references auth.users(id),
  updated_at timestamptz not null default now()
);
alter table public.midias add column if not exists r2_chave text;

create or replace function public.eh_gestor_de(colab uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists(select 1 from public.gestor_colaborador where gestor_id = auth.uid() and colaborador_id = colab)
$$;
revoke all on function public.eh_gestor_de(uuid) from public, anon;
grant execute on function public.eh_gestor_de(uuid) to authenticated;

alter table public.gestores enable row level security;
alter table public.gestor_colaborador enable row level security;
alter table public.config_envio enable row level security;

create policy gestores_ve_a_si on public.gestores for select to authenticated using (user_id = auth.uid());
create policy gc_gestor_ve on public.gestor_colaborador for select to authenticated using (gestor_id = auth.uid() or colaborador_id = auth.uid());
create policy cfg_le on public.config_envio for select to authenticated using (user_id = auth.uid() or public.eh_gestor_de(user_id));
create policy cfg_gestor_grava on public.config_envio for insert to authenticated with check (public.eh_gestor_de(user_id));
create policy cfg_gestor_altera on public.config_envio for update to authenticated using (public.eh_gestor_de(user_id)) with check (public.eh_gestor_de(user_id));
create policy midias_gestor_le on public.midias for select to authenticated using (public.eh_gestor_de(user_id));
