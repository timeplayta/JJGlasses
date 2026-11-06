"""Gera web/version.json.

- "v": número da versão = data e hora do build (ex.: 202611061830).
- Só muda o "v" quando o index.html mudou de verdade. Assim o celular não baixa nada à toa.
- "notes": título e novidades, lidos de web/notas.json (você escreve). Se não existir, usa a mensagem do commit.
- "bytes" e "sha256": tamanho e "impressão digital" do index.html, para o app conferir se baixou inteiro.
"""
import hashlib, json, os, pathlib, subprocess

web = pathlib.Path("web")
html = (web / "index.html").read_bytes()
sha = hashlib.sha256(html).hexdigest()

old = {}
if (web / "version.json").exists():
    try:
        old = json.loads((web / "version.json").read_text(encoding="utf-8"))
    except Exception:
        old = {}

if old.get("sha256") == sha:
    data = old  # jogo/app igual: mantém a versão
    print("index.html não mudou; versão", old.get("v"))
else:
    notes = None
    if (web / "notas.json").exists():
        try:
            notes = json.loads((web / "notas.json").read_text(encoding="utf-8"))
        except Exception:
            notes = None
    if not notes or not notes.get("t"):
        msg = subprocess.run(["git", "log", "-1", "--pretty=%s"], capture_output=True, text=True).stdout.strip()
        notes = {"t": "Novidades", "b": [msg or "Melhorias no app."]}
    data = {"v": int(os.environ["V"]), "notes": {"t": notes["t"], "b": list(notes.get("b", []))}, "bytes": len(html), "sha256": sha}
    print("nova versão", data["v"])

(web / "version.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
