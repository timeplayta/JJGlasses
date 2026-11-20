-- Óculos que conectam pelo Wi-Fi próprio (ex.: iVUE Denali): nome da rede e senha de fábrica.
-- O app usa para "Conectar" direto, sem a pessoa ir nas configurações de Wi-Fi.
-- (O valor da senha é gravado direto no Supabase, não fica no código.)
alter table public.oculos_modelos add column if not exists wifi_ssid text;
alter table public.oculos_modelos add column if not exists wifi_senha text;
