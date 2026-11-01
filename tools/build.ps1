# 不依赖 Gradle / AGP 的手工 APK 打包：aapt2 → javac → d8 → zipalign → apksigner。
# 好处：完全离线、产物只有几十 KB、每一步都能看着。
#
# 工具链位置不写死：优先用参数，其次环境变量（ANDROID_SDK_ROOT / ANDROID_HOME / JAVA_HOME），
# 最后在常见安装位置里找；都找不到就报错并提示怎么传。
#   pwsh -File tools\build.ps1 -SdkRoot D:\android-sdk -JdkHome D:\jdk17
param(
    [string]$AppDir = "$PSScriptRoot\..\app",
    [string]$OutDir = "$PSScriptRoot\..\out",
    [string]$SdkRoot = '',
    [string]$JdkHome = ''
)

$ErrorActionPreference = 'Stop'

function Find-Dir([string[]]$candidates, [string]$probe) {
    foreach ($c in $candidates) {
        if ([string]::IsNullOrWhiteSpace($c)) { continue }
        if (Test-Path (Join-Path $c $probe)) { return (Resolve-Path $c).Path }
    }
    return ''
}

if ([string]::IsNullOrWhiteSpace($SdkRoot)) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($SdkRoot)) { $SdkRoot = $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    $SdkRoot = Find-Dir @(
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk",
        "$env:ProgramFiles\Android\Sdk",
        'C:\Android\Sdk',
        "$PSScriptRoot\..\android-sdk",
        "$PSScriptRoot\..\..\android-sdk"
    ) 'platform-tools'
}

if ([string]::IsNullOrWhiteSpace($JdkHome)) { $JdkHome = $env:JAVA_HOME }
if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    $JdkHome = Find-Dir @(
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:ProgramFiles\Java\jdk-17",
        "$env:LOCALAPPDATA\Programs\jdk-17",
        "$PSScriptRoot\..\jdk17\jdk-17",
        "$PSScriptRoot\..\..\jdk17\jdk-17"
    ) 'bin\javac.exe'
}

if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    throw '找不到 Android SDK：请传 -SdkRoot <路径>，或设置 ANDROID_SDK_ROOT。'
}
if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    throw '找不到 JDK 17：请传 -JdkHome <路径>，或设置 JAVA_HOME。'
}

$buildTools = Get-ChildItem (Join-Path $SdkRoot 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
$platforms = Get-ChildItem (Join-Path $SdkRoot 'platforms') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $buildTools) { throw "在 $SdkRoot\build-tools 下没找到 build-tools，请先用 sdkmanager 安装。" }
if (-not $platforms) { throw "在 $SdkRoot\platforms 下没找到 Android platform，请先用 sdkmanager 安装。" }

$BuildTools = $buildTools.FullName
$AndroidJar = Join-Path $platforms.FullName 'android.jar'
$Javac = Join-Path $JdkHome 'bin\javac.exe'
$Keytool = Join-Path $JdkHome 'bin\keytool.exe'
foreach ($p in @($BuildTools, $AndroidJar, $Javac, $Keytool)) {
    if (-not (Test-Path $p)) { throw "缺少构建依赖: $p" }
}

$env:JAVA_HOME = $JdkHome
$env:PATH = (Join-Path $JdkHome 'bin') + ';' + $env:PATH

Write-Host "SDK   : $SdkRoot"
Write-Host "Tools : $BuildTools"
Write-Host "JDK   : $JdkHome"

$AppDir = (Resolve-Path $AppDir).Path
$Work = Join-Path $OutDir 'build'
$GenDir = Join-Path $Work 'gen'
$ClassDir = Join-Path $Work 'classes'
$DexDir = Join-Path $Work 'dex'
$CompiledZip = Join-Path $Work 'compiled.zip'
$BaseApk = Join-Path $Work 'base.apk'
$AlignedApk = Join-Path $Work 'aligned.apk'
$FinalApk = Join-Path $OutDir 'dsh-watch.apk'
$Keystore = Join-Path $OutDir 'dsh-watch.keystore'
$Pass = 'dshwatch'

if (Test-Path $Work) { Remove-Item $Work -Recurse -Force }
New-Item -ItemType Directory -Path $Work, $GenDir, $ClassDir, $DexDir, $OutDir -Force | Out-Null

Write-Host '[1/6] aapt2 compile'
& (Join-Path $BuildTools 'aapt2.exe') compile --dir (Join-Path $AppDir 'res') -o $CompiledZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

Write-Host '[2/6] aapt2 link'
& (Join-Path $BuildTools 'aapt2.exe') link -o $BaseApk -I $AndroidJar --manifest (Join-Path $AppDir 'AndroidManifest.xml') --java $GenDir $CompiledZip --min-sdk-version 26 --target-sdk-version 30 --version-code 2 --version-name '1.1.0'
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '[3/6] javac'
$sources = @(Get-ChildItem -Path (Join-Path $AppDir 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$rJava = @(Get-ChildItem -Path $GenDir -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
$all = $sources + $rJava
& $Javac -encoding UTF-8 --release 11 -nowarn -classpath $AndroidJar -d $ClassDir @all
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Write-Host '[4/6] d8'
$classes = @(Get-ChildItem -Path $ClassDir -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& (Join-Path $BuildTools 'd8.bat') --lib $AndroidJar --min-api 26 --release --output $DexDir @classes
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Write-Host '[5/6] 打包 dex + zipalign'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($BaseApk, 'Update')
try {
    $existing = $zip.GetEntry('classes.dex')
    if ($existing) { $existing.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip,
        (Join-Path $DexDir 'classes.dex'),
        'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal
    ) | Out-Null
} finally {
    $zip.Dispose()
}
& (Join-Path $BuildTools 'zipalign.exe') -f 4 $BaseApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

Write-Host '[6/6] 签名'
if (-not (Test-Path $Keystore)) {
    & $Keytool -genkeypair -keystore $Keystore -storepass $Pass -keypass $Pass -alias dshwatch -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=DSH Watch,O=DSH,C=CN' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '生成签名密钥失败' }
}
if (Test-Path $FinalApk) { Remove-Item $FinalApk -Force }
& (Join-Path $BuildTools 'apksigner.bat') sign --ks $Keystore --ks-pass "pass:$Pass" --key-pass "pass:$Pass" --ks-key-alias dshwatch --out $FinalApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }

& (Join-Path $BuildTools 'apksigner.bat') verify --print-certs $FinalApk | Select-Object -First 4

$size = (Get-Item $FinalApk).Length
Write-Host ''
Write-Host ("APK: {0}  ({1:N1} KB)" -f $FinalApk, ($size / 1KB))
