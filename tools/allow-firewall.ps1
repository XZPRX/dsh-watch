# 给 Windows 防火墙放行 dsh-watch 需要的端口（需要管理员，会自动请求提权）。
#   pwsh -File tools\allow-firewall.ps1          放行 TCP 8765 + UDP 8766
#   pwsh -File tools\allow-firewall.ps1 -Remove  撤销规则
param(
    [switch]$Remove,
    [int]$Port = 8765,
    [int]$DiscoveryPort = 8766
)

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host '需要管理员权限，正在提权重开…'
    $args = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Port $Port -DiscoveryPort $DiscoveryPort"
    if ($Remove) { $args += ' -Remove' }
    Start-Process powershell -Verb RunAs -ArgumentList $args
    exit
}

$rules = @(
    @{ name = "dsh-watch HTTP $Port"; proto = 'TCP'; port = $Port },
    @{ name = "dsh-watch discovery $DiscoveryPort"; proto = 'UDP'; port = $DiscoveryPort }
)

foreach ($rule in $rules) {
    & netsh advfirewall firewall delete rule name="$($rule.name)" 2>$null | Out-Null
    if ($Remove) {
        Write-Host "已删除规则：$($rule.name)"
        continue
    }
    & netsh advfirewall firewall add rule name="$($rule.name)" dir=in action=allow protocol=$($rule.proto) localport=$($rule.port) profile=any | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "已放行：$($rule.proto) $($rule.port)（入站，所有网络配置）"
    } else {
        Write-Warning "放行失败：$($rule.name)"
    }
}

if (-not $Remove) {
    Write-Host ''
    Write-Host "提示：手机/手表和电脑要在同一网段；手表 App 里「连接电脑」应该能搜到这台电脑。"
    Write-Host "如果仍然连不上，先在本机浏览器打开 http://127.0.0.1:$Port/api/ping 确认插件在跑。"
}
