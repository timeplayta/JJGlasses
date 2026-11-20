@echo off
rem Procura a imagem ao vivo do iVUE pelo Wi-Fi (so le). Use com o PC na rede "iVUE Denali". Resultado: ivue-wifi.txt
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0procurar-ivue-wifi.ps1"
