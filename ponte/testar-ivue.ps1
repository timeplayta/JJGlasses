# Teste do iVUE Denali (só LÊ informações, não muda nada no PC)
# Descobre como o óculos aparece no Windows: como webcam, como pendrive (pasta DCIM) ou pelo Wi-Fi dele.
# O resultado fica em ponte\ivue-teste.txt
$o = Join-Path $PSScriptRoot "ivue-teste.txt"
"Teste do iVUE - $(Get-Date)" | Out-File $o -Encoding utf8

"`n== 1) Cameras (webcam USB, UVC) e aparelhos ligados ==" | Add-Content $o
Get-PnpDevice -PresentOnly | Where-Object { $_.Class -in 'Camera','Image','WPD','DiskDrive','USB','MEDIA' } |
  Format-Table Class, FriendlyName, Status -AutoSize | Out-String -Width 250 | Add-Content $o

"`n== 2) Unidades removiveis (pendrive, cartao, oculos) ==" | Add-Content $o
$drv = Get-Volume | Where-Object { $_.DriveLetter -and $_.DriveType -eq 'Removable' }   # so as removiveis: nao lista os seus discos
$drv | Format-Table DriveLetter, FileSystemLabel, DriveType, Size, SizeRemaining -AutoSize | Out-String | Add-Content $o
if (-not $drv) { "nenhuma unidade removivel" | Add-Content $o }
foreach ($d in $drv) {
  "--- videos e fotos em $($d.DriveLetter): ---" | Add-Content $o
  Get-ChildItem "$($d.DriveLetter):\" -Recurse -File -Include *.mp4,*.mov,*.jpg,*.txt -ErrorAction SilentlyContinue | Select-Object -First 60 FullName, Length, LastWriteTime |
    Format-Table -AutoSize | Out-String -Width 250 | Add-Content $o
}

"`n== 3) Redes Wi-Fi do oculos ==" | Add-Content $o
netsh wlan show networks | Select-String -Pattern 'ivue|denali|glide|cam' | Out-String | Add-Content $o

"`n== 4) ffmpeg instalado? ==" | Add-Content $o
$ff = Get-Command ffmpeg -ErrorAction SilentlyContinue
if ($ff) { "sim: $($ff.Source)" | Add-Content $o } else { "nao" | Add-Content $o }
"`n== 5) Node.js instalado? ==" | Add-Content $o
$nd = Get-Command node -ErrorAction SilentlyContinue
if ($nd) { "sim: $(& node --version)" | Add-Content $o } else { "nao" | Add-Content $o }

"Pronto: $o"
