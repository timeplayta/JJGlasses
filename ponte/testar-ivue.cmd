@echo off
rem Roda o teste do iVUE (so le informacoes). Resultado: ivue-teste.txt nesta pasta.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0testar-ivue.ps1"
