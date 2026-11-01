# 等手表上出现审批卡后自动点「允许」，用来验证「桌面端 apiproxy 并存时手表审批仍然生效」。
# 用法：pwsh -File test\watch-approve.ps1 -Token <配对令牌> [-Port 8765]
param(
    [string]$Token = 'mock-token',
    [int]$Port = 8765,
    [string]$Adb = 'adb'
)

$phone = "http://127.0.0.1:$Port"
$shot = Join-Path $PSScriptRoot '..\out\approval.png'
$deadline = (Get-Date).AddSeconds(70)
$seen = $false

while ((Get-Date) -lt $deadline) {
    try {
        $raw = (Invoke-WebRequest "$phone/api/state?token=$Token" -TimeoutSec 3 -UseBasicParsing).Content
        if ($raw -match '"pending":\[\{') { $seen = $true; break }
    } catch { }
    Start-Sleep -Milliseconds 400
}

if (-not $seen) {
    Write-Output 'NO_PENDING_APPROVAL'
    exit 0
}

Start-Sleep -Milliseconds 600
& $Adb shell screencap -p /sdcard/approval.png | Out-Null
& $Adb pull /sdcard/approval.png $shot | Out-Null
# 方屏布局下「允许」按钮中心（换表/换布局请改这两个坐标）
& $Adb shell input tap 98 434
Start-Sleep -Milliseconds 800

$after = (Invoke-WebRequest "$phone/api/state?token=$Token" -TimeoutSec 3 -UseBasicParsing).Content
if ($after -match '"pending":\[\]') { Write-Output 'APPROVED_FROM_WATCH' } else { Write-Output 'STILL_PENDING' }
