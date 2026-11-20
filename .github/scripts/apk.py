"""Gera web/apk.json: diz aos celulares se tem APK novo para atualizar sozinho.

- "code": número do APK que todo mundo precisa ter. Só muda quando o código nativo
  (pasta android, em Java) muda de verdade. Se mudou só a tela (index.html), ninguém
  precisa atualizar o APK, porque a tela já se atualiza sozinha.
- "bytes" e "sha256": tamanho e "impressão digital" do JJGlasses.apk publicado, para o
  celular conferir se baixou inteiro antes de instalar.
- "notes": novidades (de web/notas.json), mostradas na notificação e na tela de atualizar.
"""
import hashlib, json, os, pathlib, subprocess

files = subprocess.run(["git", "ls-files", "android"], capture_output=True, text=True).stdout.split()
h = hashlib.sha256()
for name in sorted(files):
    if "/assets/" in name:  # cópia offline do app: muda toda hora, não é código nativo
        continue
    h.update(name.encode())
    h.update(pathlib.Path(name).read_bytes())
src = h.hexdigest()

apk = pathlib.Path("apk/JJGlasses.apk").read_bytes()
out = pathlib.Path("web/apk.json")
old = {}
if out.exists():
    try:
        old = json.loads(out.read_text(encoding="utf-8"))
    except Exception:
        old = {}

code = int(os.environ["VERSION_CODE"])
name = os.environ["VERSION_NAME"]
if old.get("src") != src:
    notes = {}
    try:
        notes = json.loads(pathlib.Path("web/notas.json").read_text(encoding="utf-8"))
    except Exception:
        pass
    old = {"code": code, "name": name, "src": src, "notes": notes}
    print("código nativo mudou: todos atualizam para o APK", name)
else:
    print("código nativo igual: continua pedindo o APK", old.get("name"))

data = dict(old, bytes=len(apk), sha256=hashlib.sha256(apk).hexdigest(), atual=code)
out.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
