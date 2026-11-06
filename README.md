# JJGlasses — app que se atualiza sozinho

O JJGlasses agora tem duas partes:

| Parte | Onde fica | O que é |
| --- | --- | --- |
| **Casca** (shell, "casca") | `android/` | App Android pequeno, em Java. Abre o JJGlasses em tela cheia dentro de um **WebView** (navegador embutido) e empresta microfone, voz do celular e permissões. |
| **App** | `web/index.html` | Todo o JJGlasses em **HTML5** (a linguagem das páginas web): telas, chat, ligação, ajustes. É um arquivo só, com tudo dentro. |
| **Servidor** | `supabase/functions/` | **Edge Functions** (pequenos programas que rodam no Supabase): `chat` fala com a IA Kimi e `voz` faz a voz humana. As chaves ficam guardadas lá, nunca no app. |

## Como a atualização funciona

1. Você muda o `web/index.html` e faz **push** (enviar para o GitHub).
2. O **GitHub Actions** (robô do GitHub, em `.github/workflows/build.yml`):
   - cria o número da versão com a data e a hora (ex.: `202611061830`);
   - escreve o `web/version.json` com a versão, as novidades de `web/notas.json`, o tamanho e a "impressão digital" (SHA-256) do `index.html`;
   - monta o **APK assinado** e guarda em `apk/JJGlasses.apk`;
   - publica o `index.html`, o `version.json` e o APK no site: `https://timeplayta.github.io/jjglasses-app/`.
3. No celular, ao abrir, a casca lê o `version.json` (espera no máximo uns 3 segundos).
   - Versão do site maior que a do celular → baixa o `index.html` novo, confere se veio inteiro, salva e abre. Mostra **"App atualizado!"** com as novidades.
   - Sem internet ou com erro → abre a última versão salva (ou a cópia que veio dentro do APK).
4. Com o app aberto, a cada 10 minutos ela confere de novo. Se sair versão nova, baixa e mostra **"Atualizar agora"**.

## Quando precisa reinstalar o APK

| Mudou… | Precisa reinstalar? |
| --- | --- |
| Qualquer coisa no `web/index.html` (telas, textos, cores, IA, vozes, ligação, botões) | **Não.** Chega sozinho. |
| Edge Functions do Supabase (`chat`, `voz`) | **Não.** Muda no servidor na hora. |
| A casca: código Java (`android/`), permissões, ícone, nome do app, orientação da tela | **Sim.** Baixe o `apk/JJGlasses.apk` novo e instale por cima. Não precisa desinstalar, porque a assinatura é a mesma. |

## Para escrever as novidades

Antes do push, edite `web/notas.json`:

```json
{ "t": "Título da novidade", "b": ["Novidade 1", "Novidade 2"] }
```

`t` = title (título) e `b` = bullets (itens da lista).

## Segredos (secrets) do GitHub que o robô usa

`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. É a chave de assinatura do APK. Uma cópia está no PC do Juan, em `new robot\chave-assinatura`. **Não perca:** sem ela, o próximo APK só instala desinstalando o app antes.
