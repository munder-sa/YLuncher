$ErrorActionPreference = 'Stop'
$taskSdk = 'C:\Users\munde\AppData\Local\Android\Sdk'
$taskJdk = 'C:\Program Files\Android\Android Studio\jbr'
$taskTools = Join-Path $taskSdk 'build-tools\36.0.0'
$taskPlatform = Join-Path $taskSdk 'platforms\android-35\android.jar'
$taskBuild = Join-Path $PSScriptRoot 'build'
$taskOutput = 'C:\Users\munde\Documents\Codex\2026-11-04\new-chat\outputs'
New-Item -ItemType Directory -Force -Path $taskBuild,(Join-Path $taskBuild 'classes'),(Join-Path $taskBuild 'dex'),$taskOutput | Out-Null
function Check-Exit([string]$stage) { if ($LASTEXITCODE -ne 0) { throw "$stage failed ($LASTEXITCODE)" } }
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $taskJdk 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -Xlint:-options -classpath $taskPlatform -d (Join-Path $taskBuild 'classes') @taskSources
Check-Exit 'javac'
& (Join-Path $taskJdk 'bin\jar.exe') "-J-Djava.io.tmpdir=$taskBuild" cf (Join-Path $taskBuild 'classes.jar') -C (Join-Path $taskBuild 'classes') .
Check-Exit 'jar'
& (Join-Path $taskJdk 'bin\java.exe') "-Djava.io.tmpdir=$taskBuild" -cp (Join-Path $taskTools 'lib\d8.jar') com.android.tools.r8.D8 --lib $taskPlatform --min-api 26 --output (Join-Path $taskBuild 'dex') (Join-Path $taskBuild 'classes.jar')
Check-Exit 'd8'
& (Join-Path $taskTools 'aapt2.exe') link -I $taskPlatform --manifest (Join-Path $PSScriptRoot 'AndroidManifest.xml') -o (Join-Path $taskBuild 'unsigned.apk')
Check-Exit 'aapt2'
& (Join-Path $taskJdk 'bin\jar.exe') "-J-Djava.io.tmpdir=$taskBuild" uf (Join-Path $taskBuild 'unsigned.apk') -C (Join-Path $taskBuild 'dex') classes.dex
Check-Exit 'package dex'
& (Join-Path $taskTools 'zipalign.exe') -f 4 (Join-Path $taskBuild 'unsigned.apk') (Join-Path $taskBuild 'aligned.apk')
Check-Exit 'zipalign'
$taskKey = Join-Path $PSScriptRoot 'guard-signing.p12'
if (!(Test-Path -LiteralPath $taskKey)) {
    & (Join-Path $taskJdk 'bin\keytool.exe') -genkeypair -keystore $taskKey -storetype PKCS12 -storepass local-gt8d-guard -alias guard -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=GT8D IPv6 Guard Local'
    Check-Exit 'keytool'
}
& (Join-Path $taskJdk 'bin\java.exe') -jar (Join-Path $taskTools 'lib\apksigner.jar') sign --ks $taskKey --ks-pass pass:local-gt8d-guard --ks-key-alias guard --out (Join-Path $taskOutput 'GT8D-IPv6-Guard.apk') (Join-Path $taskBuild 'aligned.apk')
Check-Exit 'apksigner'
& (Join-Path $taskJdk 'bin\java.exe') -jar (Join-Path $taskTools 'lib\apksigner.jar') verify --verbose (Join-Path $taskOutput 'GT8D-IPv6-Guard.apk')
Check-Exit 'verify'
Get-FileHash -LiteralPath (Join-Path $taskOutput 'GT8D-IPv6-Guard.apk') -Algorithm SHA256
