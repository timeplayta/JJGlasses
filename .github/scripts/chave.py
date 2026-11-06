"""Monta a chave de assinatura a partir dos segredos do GitHub.

Se, ao colar, foram junto textos extras (como "Nome:" ou "Segredo (valor):"),
este script acha só a parte certa: o trecho longo em base64 e a senha que abre a chave.
Nunca mostra os valores no log.
"""
import base64, os, re, subprocess, sys

raw_key = os.environ.get("KB", "")
raw_pass = os.environ.get("KP", "")
out = os.path.join(os.environ["RUNNER_TEMP"], "jjglasses.jks")

if not raw_key.strip():
    print("::error::Falta o segredo KEYSTORE_BASE64.")
    sys.exit(1)

# trecho base64 mais longo (a chave tem ~3500 letras)
runs = sorted(re.findall(r"[A-Za-z0-9+/=\s]{200,}", raw_key), key=lambda r: len(re.sub(r"\s", "", r)), reverse=True)
data = None
for r in runs:
    clean = re.sub(r"\s", "", r)
    try:
        data = base64.b64decode(clean, validate=True)
        break
    except Exception:
        continue
if not data:
    print("::error::O segredo KEYSTORE_BASE64 não tem uma chave válida.")
    sys.exit(1)
open(out, "wb").write(data)

# senhas possíveis: o texto inteiro e cada "palavra" dele
cands = [raw_pass.strip()] + re.findall(r"[A-Za-z0-9_\-]{8,}", raw_pass)
for c in cands:
    if not c:
        continue
    ok = subprocess.run(["keytool", "-list", "-keystore", out, "-storepass", c], capture_output=True).returncode == 0
    if ok:
        print(f"::add-mask::{c}")
        with open(os.environ["GITHUB_ENV"], "a") as env:
            env.write(f"KEYSTORE_FILE={out}\nKEYSTORE_PASSWORD={c}\nKEY_PASSWORD={c}\nKEY_ALIAS=jjglasses\n")
        print("Chave e senha conferidas.")
        sys.exit(0)
print("::error::A senha (KEYSTORE_PASSWORD) não abre a chave.")
sys.exit(1)
