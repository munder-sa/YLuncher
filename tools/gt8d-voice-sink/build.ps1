param(
    [string]$AndroidSdk = (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk'),
    [string]$SigningKeyDirectory = (Join-Path $env:USERPROFILE '.codex\visualizations\2026\11\06\01a1b125-7edb-7f13-a211-b513397e4251\gt8d-voice-sink-signing'))
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$buildTools = Join-Path $AndroidSdk 'build-tools\36.0.0'
$androidJar = Join-Path $AndroidSdk 'platforms\android-35\android.jar'
$jdk = 'C:\Program Files\Android\Android Studio\jbr\bin'
$out = Join-Path $project 'build'
New-Item -ItemType Directory -Force $out | Out-Null
function Invoke-Checked([string]$program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) { throw "$program failed: $LASTEXITCODE" }
}
$compiled = Join-Path $out 'resources.zip'
Invoke-Checked "$buildTools\aapt2.exe" @('compile','--dir',"$project\res",'-o',$compiled)
$unsigned = Join-Path $out 'unsigned.apk'
Invoke-Checked "$buildTools\aapt2.exe" @('link','-I',$androidJar,'--manifest',"$project\AndroidManifest.xml",'--version-code','1','--version-name','0.1.0','-o',$unsigned,$compiled)
$classes = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
New-Item -ItemType Directory -Force $classes,$dex | Out-Null
$sources = @(Get-ChildItem "$project\src" -Recurse -Filter '*.java' | ForEach-Object FullName)
Invoke-Checked "$jdk\javac.exe" (@('-encoding','UTF-8','--release','8','-classpath',$androidJar,'-d',$classes) + $sources)
$classFiles = @(Get-ChildItem $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
$oldJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = Split-Path $jdk
    Invoke-Checked "$buildTools\d8.bat" (@('--min-api','35','--lib',$androidJar,'--output',$dex) + $classFiles)
} finally { $env:JAVA_HOME = $oldJavaHome }
Invoke-Checked "$jdk\jar.exe" @('uf',$unsigned,'-C',$dex,'classes.dex')
$aligned = Join-Path $out 'aligned.apk'
Invoke-Checked "$buildTools\zipalign.exe" @('-f','4',$unsigned,$aligned)
$key = Join-Path $SigningKeyDirectory 'debug.p12'
if (!(Test-Path -LiteralPath $key)) {
    New-Item -ItemType Directory -Force $SigningKeyDirectory | Out-Null
    Invoke-Checked "$jdk\keytool.exe" @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','gt8d-voice-sink','-dname','CN=GT8D Voice Sink Development','-keyalg','RSA','-keysize','2048','-validity','10000','-noprompt')
}
$apk = Join-Path $out 'GT8D-Voice-Key-Sink-0.1.0.apk'
try {
    $env:JAVA_HOME = Split-Path $jdk
    Invoke-Checked "$buildTools\apksigner.bat" @('sign','--ks',$key,'--ks-key-alias','gt8d-voice-sink','--ks-pass','pass:android','--key-pass','pass:android','--out',$apk,$aligned)
    Invoke-Checked "$buildTools\apksigner.bat" @('verify','--verbose',$apk)
} finally { $env:JAVA_HOME = $oldJavaHome }
Get-FileHash -Algorithm SHA256 -LiteralPath $apk
