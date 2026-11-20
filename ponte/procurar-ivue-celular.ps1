# Procura o iVUE Denali usando o CELULAR (que está no Wi-Fi do óculos) ligado no PC pelo cabo USB.
$ErrorActionPreference = "Continue"
# Usa o ADB (Android Debug Bridge = "ponte de depuração do Android"): o PC manda o celular perguntar ao óculos.
# Só LÊ, não muda nada no celular nem no óculos. Resultado: ponte\ivue-celular.txt
$o = Join-Path $PSScriptRoot "ivue-celular.txt"
"Procura do iVUE pelo celular - $(Get-Date)" | Out-File $o -Encoding utf8

# acha o adb.exe (no PATH ou nas pastas comuns do Android Studio / platform-tools)
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
foreach ($c in "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", "$env:USERPROFILE\platform-tools\adb.exe", "$env:USERPROFILE\Downloads\platform-tools\adb.exe", "C:\platform-tools\adb.exe", "$PSScriptRoot\platform-tools\adb.exe") {
  if (-not $adb -and (Test-Path $c)) { $adb = $c }
}
if (-not $adb) { "ADB nao encontrado no PC." | Add-Content $o; "Pronto: $o"; exit }
"adb: $adb" | Add-Content $o

"`n== 1) Celular ligado no cabo? ==" | Add-Content $o
& $adb devices -l 2>&1 | Out-String | Add-Content $o

"`n== 1b) Versao do JJGlasses instalada no celular ==" | Add-Content $o
& $adb shell "dumpsys package com.jjglasses.app | grep -m 2 -E 'versionName|lastUpdateTime'" 2>&1 | Out-String | Add-Content $o

"`n== 2) Wi-Fi do celular (nome da rede e endereco) ==" | Add-Content $o
& $adb shell "dumpsys wifi | grep -m 3 -E 'mWifiInfo|SSID:'" 2>&1 | Out-String | Add-Content $o
& $adb shell "ip route" 2>&1 | Out-String | Add-Content $o
$rota = (& $adb shell "ip route" 2>&1 | Out-String)
$gw = ([regex]::Match($rota, 'via (\d+\.\d+\.\d+\.\d+)')).Groups[1].Value
$rede = ([regex]::Match($rota, '(\d+\.\d+\.\d+)\.\d+/\d+ dev wlan0')).Groups[1].Value
if (-not $gw) { $gw = '192.168.1.254' }   # o iVUE Denali fica neste endereco (achado no 1o teste)
"endereco do oculos: $gw   rede: $rede" | Add-Content $o

$ips = @($gw)
$portas = 80, 554, 8080, 8192, 3333, 6666, 7878, 8554, 9876, 5000, 8888

"`n== 3) Portas abertas no oculos (vistas pelo celular) ==" | Add-Content $o
foreach ($ip in $ips) {
  & $adb shell "ping -c 1 -W 1 $ip >/dev/null 2>&1 && echo '$ip responde ao ping' || echo '$ip nao responde ao ping'" 2>&1 | Out-String | Add-Content $o
  foreach ($p in $portas) {
    $r = & $adb shell "nc -z -w 1 $ip $p >/dev/null 2>&1 && echo ABERTA" 2>&1 | Out-String
    if ($r -match 'ABERTA') { "$($ip):$p ABERTA" | Add-Content $o }
  }
}

"`n== 4) Perguntas HTTP ao oculos (pelo celular) - so leitura ==" | Add-Content $o
# comandos de LEITURA do chip Novatek: 3012 versao, 3014 todas as configuracoes, 3015 lista de arquivos,
# 3016 "esta vivo?", 3017 estado do cartao, 3019 bateria, 3029 nome do Wi-Fi, 3002 lista de comandos, 3024 espaco livre.
# Status -22 = "estado errado" (o oculos esta num modo que nao aceita o pedido, ex.: gravando ou ligado no PC). Nada de gravar/apagar/mudar modo.
foreach ($cam in '/', '/?custom=1&cmd=3012', '/?custom=1&cmd=3014', '/?custom=1&cmd=3016', '/?custom=1&cmd=3017', '/?custom=1&cmd=3019', '/?custom=1&cmd=3029', '/?custom=1&cmd=3015', '/?custom=1&cmd=3002', '/?custom=1&cmd=3024', '/DCIM/', '/DCIM/Movie', '/DCIM/Photo', '/NOVATEK/') {
  "--- http://$($gw)$cam" | Add-Content $o
  $r = & $adb shell "printf 'GET $cam HTTP/1.0\r\nHost: $gw\r\n\r\n' | nc -w 3 $gw 80 | head -c 2500" 2>&1 | Out-String
  $r | Add-Content $o
}
"`n== 5) Video ao vivo (RTSP) ==" | Add-Content $o
foreach ($cam in '/', '/xxx.mov', '/xxx.mp4', '/live', '/live.sdp', '/stream', '/liveRTSP/av4', '/av0', '/H264', '/1', '/main', '/preview') {
  "--- RTSP DESCRIBE rtsp://$($gw):554$cam" | Add-Content $o
  & $adb shell "printf 'DESCRIBE rtsp://$($gw):554$cam RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n\r\n' | nc -w 3 $gw 554 | head -c 900" 2>&1 | Out-String | Add-Content $o
}
"`n== 6) Porta 3333 (avisos do oculos) ==" | Add-Content $o
& $adb shell "nc -w 3 $gw 3333 </dev/null | head -c 300" 2>&1 | Out-String | Add-Content $o
"Pronto: $o"
