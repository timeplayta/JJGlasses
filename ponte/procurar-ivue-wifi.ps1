# Procura como o iVUE Denali manda a imagem ao vivo e a lista de vídeos pelo Wi-Fi dele.
# Use com o PC conectado na rede "iVUE Denali". Só LÊ (pergunta "você está aí?" para o óculos), não muda nada.
# O resultado fica em ponte\ivue-wifi.txt
$o = Join-Path $PSScriptRoot "ivue-wifi.txt"
"Procura do iVUE pelo Wi-Fi - $(Get-Date)" | Out-File $o -Encoding utf8

"`n== 1) Rede Wi-Fi conectada agora ==" | Add-Content $o
netsh wlan show interfaces | Select-String -Pattern ' SSID|Estado|State|Sinal|Signal' | Out-String | Add-Content $o
$gw = (Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Sort-Object RouteMetric | Select-Object -First 1).NextHop
$meu = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254*' } | Select-Object -ExpandProperty IPAddress) -join ', '
"meu IP: $meu   |   gateway (provavel endereco do oculos): $gw" | Add-Content $o

# endereços comuns de câmeras e óculos com Wi-Fi próprio + o gateway da rede
$ips = @($gw, '192.168.1.254', '192.168.0.1', '192.168.1.1', '192.168.25.1', '192.168.169.1', '192.168.42.1', '192.72.1.1') | Where-Object { $_ } | Select-Object -Unique
$portas = 80, 554, 8080, 8192, 3333, 6666, 7878, 8554, 9876, 5000, 5555, 8888, 9999

function PortaAberta($ip, $porta) {
  $c = New-Object System.Net.Sockets.TcpClient
  try { $r = $c.BeginConnect($ip, $porta, $null, $null); $ok = $r.AsyncWaitHandle.WaitOne(400) -and $c.Connected; return $ok }
  catch { return $false } finally { $c.Close() }
}
function PerguntarHttp($url) {
  try { $r = Invoke-WebRequest -Uri $url -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
        return "HTTP $($r.StatusCode) tipo=$($r.Headers['Content-Type']) tamanho=$($r.RawContentLength)`n" + ($r.Content | Out-String).Substring(0, [Math]::Min(600, ($r.Content | Out-String).Length)) }
  catch { return "erro: $($_.Exception.Message)" }
}

"`n== 2) Portas abertas (por onde o oculos conversa) ==" | Add-Content $o
$abertas = @()
foreach ($ip in $ips) { foreach ($p in $portas) { if (PortaAberta $ip $p) { "$($ip):$p ABERTA" | Add-Content $o; $abertas += ,@($ip, $p) } } }
if (-not $abertas) { "nenhuma porta aberta encontrada (o PC esta na rede do oculos?)" | Add-Content $o }

"`n== 3) Perguntas HTTP (lista de arquivos, estado, imagem ao vivo) ==" | Add-Content $o
foreach ($a in $abertas) {
  $ip = $a[0]; $p = $a[1]
  if ($p -in 554, 8554) {
    # RTSP: pergunta educada "OPTIONS" (só pergunta o que o servidor de vídeo aceita)
    try { $c = New-Object System.Net.Sockets.TcpClient($ip, $p); $s = $c.GetStream(); $s.ReadTimeout = 2000
          $msg = [Text.Encoding]::ASCII.GetBytes("OPTIONS rtsp://$($ip):$p/ RTSP/1.0`r`nCSeq: 1`r`n`r`n"); $s.Write($msg, 0, $msg.Length)
          $buf = New-Object byte[] 1024; $n = $s.Read($buf, 0, 1024); "RTSP $($ip):$p ->`n" + [Text.Encoding]::ASCII.GetString($buf, 0, $n) | Add-Content $o; $c.Close() }
    catch { "RTSP $($ip):$p erro: $($_.Exception.Message)" | Add-Content $o }
    continue
  }
  foreach ($cam in '/', '/DCIM/', '/?custom=1&cmd=3014', '/?custom=1&cmd=3012', '/?custom=1&cmd=2016', '/live', '/stream', '/cgi-bin/hi3510/param.cgi?cmd=getserverinfo') {
    "--- http://$($ip):$p$cam" | Add-Content $o
    PerguntarHttp "http://$($ip):$p$cam" | Add-Content $o
  }
}
"Pronto: $o"
