-- O gestor pode ver os arquivos (bucket midias) dos colaboradores dele e listar quem são.
create policy "midias: gestor le" on storage.objects for select to authenticated
  using (bucket_id = 'midias'
    and (storage.foldername(name))[1] ~ '^[0-9a-f-]{36}$'
    and public.eh_gestor_de(((storage.foldername(name))[1])::uuid));

create or replace function public.meus_colaboradores()
returns table (id uuid, email text, nome text)
language sql stable security definer set search_path = public, auth as $$
  select u.id, u.email::text, coalesce(nullif(p.display_name,''), split_part(u.email,'@',1))
  from public.gestor_colaborador gc
  join auth.users u on u.id = gc.colaborador_id
  left join public.profiles p on p.id = u.id
  where gc.gestor_id = auth.uid()
  order by 3
$$;
revoke all on function public.meus_colaboradores() from public, anon;
grant execute on function public.meus_colaboradores() to authenticated;
