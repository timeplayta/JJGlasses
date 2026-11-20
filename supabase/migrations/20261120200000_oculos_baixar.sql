-- Como cada chat baixa os vídeos e fotos do óculos (iVUE Denali):
--   'auto'   = quando o óculos conecta, baixa tudo para o celular e apaga do óculos (libera espaço)
--   'manual' = só baixa quando a pessoa toca em "Baixar" (padrão)
alter table public.conversas add column if not exists oculos_baixar text
  check (oculos_baixar in ('auto','manual'));
