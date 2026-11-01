# 手动起一条外网隧道（插件自己起隧道失败时的兜底）。
# 用法：pwsh -File tools\tunnel.ps1           # 默认转发 8765
#       pwsh -File tools\tunnel.ps1 -Port 8765
#
# 拿到地址后会自动写入 %USERPROFILE%\.dsh\dsh-watch-remote.txt，
# 插件每 10 秒读一次这个文件，于是 /api/state.remote 会自动更新，手表在局域网里就能学到。
# 也可以把地址直接填进插件配置的 remoteUrl。
param(
    [int]$Port = 8765,
    [string]$TunnelHost = 'localhost.run',
    [int]$SshPort = 22,
    [string]$User = 'nokey'
)

$ssh = (Get-Command ssh -ErrorAction SilentlyContinue).Source
if (-not $ssh) { throw '找不到 ssh.exe（Windows 10+ 自带 OpenSSH 客户端）' }

$remoteFile = Join-Path $env:USERPROFILE '.dsh\dsh-watch-remote.txt'
$outFile = Join-Path $env:TEMP "dsh-watch-tunnel-$PID.out.txt"
$errFile = "$outFile.err"

Write-Host "隧道：ssh -R 80:127.0.0.1:$Port -p $SshPort $User@$TunnelHost" -ForegroundColor DarkGray
Write-Host '等出现 https://xxxx.lhr.life 就是外网地址（Ctrl+C 结束）。' -ForegroundColor DarkGray

$proc = Start-Process -FilePath $ssh -PassThru -NoNewWindow `
    -ArgumentList @(
        '-o', 'StrictHostKeyChecking=no',
        '-o', 'UserKnownHostsFile=NUL',
        '-o', 'ServerAliveInterval=30',
        '-o', 'ExitOnForwardFailure=yes',
        '-R', "80:127.0.0.1:$Port",
        '-p', "$SshPort",
        "$User@$TunnelHost"
    ) -RedirectStandardOutput $outFile -RedirectStandardError $errFile

$url = ''
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 500
    $text = @()
    if (Test-Path $outFile) { $text += Get-Content $outFile -Raw -ErrorAction SilentlyContinue }
    if (Test-Path $errFile) { $text += Get-Content $errFile -Raw -ErrorAction SilentlyContinue }
    $joined = ($text -join "`n")
    if ($joined -match 'tunneled with tls termination,?\s*(https://[^\s"'']+)') {
        $url = $Matches[1].TrimEnd(')', '.', ',', ';')
        break
    }
    if ($proc.HasExited) {
        Write-Host $joined -ForegroundColor Red
        throw "ssh 提前退出（code $($proc.ExitCode)）"
    }
}

if (-not $url) {
    Write-Warning '30 秒内没拿到地址，检查这台电脑能不能 ssh 出网（22 端口）。'
} else {
    New-Item -ItemType Directory -Path (Split-Path $remoteFile) -Force | Out-Null
    Set-Content -Path $remoteFile -Value $url -Encoding ASCII
    Write-Host ''
    Write-Host "外网地址：$url" -ForegroundColor Green
    Write-Host "已写入：$remoteFile（插件 10 秒内会读到，手表在局域网里会自动学会）"
    Write-Host '手表「连接电脑 → 手动输入」：服务器填这个地址，配对码照常 6 位。'
    Write-Host ''
}

Write-Host '隧道保持中…… Ctrl+C 结束。' -ForegroundColor DarkGray
$proc.WaitForExit()
Remove-Item $outFile, $errFile -ErrorAction SilentlyContinue
