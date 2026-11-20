@echo off
rem Procura o iVUE pelo CELULAR ligado no cabo (so le). Resultado: ivue-celular.txt
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0procurar-ivue-celular.ps1"
