-- Armazenamento (bucket) PRIVADO das fotos e miniaturas da galeria do óculos.
-- Cada pessoa só lê e grava na própria pasta: midias/<id da pessoa>/...
-- Limite de 50 MB por arquivo (limite do plano grátis do Supabase).
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('midias', 'midias', false, 52428800, array['image/jpeg','image/png','image/webp','video/mp4'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit, allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "midias: dono le" on storage.objects;
drop policy if exists "midias: dono envia" on storage.objects;
drop policy if exists "midias: dono troca" on storage.objects;
drop policy if exists "midias: dono apaga" on storage.objects;
create policy "midias: dono le" on storage.objects for select to authenticated
  using (bucket_id = 'midias' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "midias: dono envia" on storage.objects for insert to authenticated
  with check (bucket_id = 'midias' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "midias: dono troca" on storage.objects for update to authenticated
  using (bucket_id = 'midias' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "midias: dono apaga" on storage.objects for delete to authenticated
  using (bucket_id = 'midias' and (storage.foldername(name))[1] = (select auth.uid())::text);

-- Ficha da mídia: de onde veio (nome no óculos, tamanho) para não repetir o mesmo arquivo
alter table public.midias add column if not exists origem text;          -- ex.: 'ivue:/DCIM/Movie/2026..._000001.MP4'
alter table public.midias add column if not exists tamanho_bytes bigint;
create unique index if not exists midias_user_origem on public.midias (user_id, origem) where origem is not null;
