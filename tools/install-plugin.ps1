# 把 dsh-watch 装进一个 DSH profile（带备份，可重复执行）
param(
    [string]$Profile = 'desktop',
    [string]$DshHome = "$env:USERPROFILE\.dsh",
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'

$pluginSrc = (Resolve-Path (Join-Path $PSScriptRoot '..\plugin')).Path
$profileDir = Join-Path $DshHome "profiles\$Profile"
$patchFile = Join-Path $profileDir 'cordis.patch.yml'
$targetDir = Join-Path $profileDir 'dsh-watch'
$backupFile = "$patchFile.bak-dshwatch"

if (-not (Test-Path $profileDir)) {
    throw "找不到 profile: $profileDir（先让 DSH 用过这个 profile）"
}

if ($Remove) {
    if (Test-Path $backupFile) {
        Copy-Item $backupFile $patchFile -Force
        Write-Host "已从备份还原 $patchFile"
    } else {
        Write-Warning "没有备份文件，请手动删掉 $patchFile 里的 dsh-watch 段落"
    }
    if (Test-Path $targetDir) { Remove-Item $targetDir -Recurse -Force }
    Write-Host "已移除 $targetDir。重启 DSH 生效。"
    exit 0
}

New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
Copy-Item (Join-Path $pluginSrc 'index.js') $targetDir -Force
Copy-Item (Join-Path $pluginSrc 'package.json') $targetDir -Force
Copy-Item (Join-Path $pluginSrc 'cordis.patch.yml') $targetDir -Force
if (Test-Path (Join-Path $pluginSrc 'client.js')) {
    Copy-Item (Join-Path $pluginSrc 'client.js') $targetDir -Force
}

if (-not (Test-Path $patchFile)) {
    Set-Content -Path $patchFile -Value "# user patch layer`n[]" -Encoding UTF8
}

if (-not (Test-Path $backupFile)) {
    Copy-Item $patchFile $backupFile -Force
}

if (Select-String -Path $patchFile -Pattern 'id:\s*dsh-watch' -Quiet) {
    Write-Host "cordis.patch.yml 里已经有 dsh-watch，跳过插入"
} else {
    $entry = @"

# --- dsh-watch (watch remote; delete this block to roll back) ---
- insert:
    - id: dsh-watch
      name: ./dsh-watch/index.js
      config:
        port: 8765
        bind: 0.0.0.0
        approvalMode: watch
        approvalTimeoutMs: 60000
"@
    Add-Content -Path $patchFile -Value $entry -Encoding UTF8
    Write-Host "已把 dsh-watch 插入 $patchFile"
}

Write-Host "插件已复制到 $targetDir"
Write-Host "重启 DSH 后检查启动日志里的: [dsh-watch] 监听 http://0.0.0.0:8765 （令牌 ...）"
Write-Host "令牌文件: $(Join-Path $DshHome 'dsh-watch.json')"
Write-Host "回滚: pwsh -File tools\install-plugin.ps1 -Profile $Profile -Remove"
