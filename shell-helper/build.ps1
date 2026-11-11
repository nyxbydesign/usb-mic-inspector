param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$BuildToolsVersion = '35.0.0',
    [string]$CompileSdk = 'android-35'
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($SdkRoot)) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($SdkRoot)) { throw 'Set ANDROID_HOME or pass -SdkRoot.' }
$androidJar = Join-Path $SdkRoot "platforms\$CompileSdk\android.jar"
$d8 = Join-Path $SdkRoot "build-tools\$BuildToolsVersion\d8.bat"
if (!(Test-Path -LiteralPath $androidJar -PathType Leaf)) { throw "Missing Android SDK jar: $androidJar" }
if (!(Test-Path -LiteralPath $d8 -PathType Leaf)) { throw "Missing D8: $d8" }

$helperRoot = $PSScriptRoot
$buildRoot = Join-Path $helperRoot 'build'
$classes = Join-Path $buildRoot 'classes'
$source = Join-Path $helperRoot 'src\dev\usbmic\inspector\shell\ShellAudioProbe.java'
$classJar = Join-Path $buildRoot 'probe-classes.jar'
$dexJar = Join-Path $buildRoot 'usb-audio-shell-probe.jar'
New-Item -ItemType Directory -Path $classes -Force | Out-Null

$compilerOutput = & javac @('--release', '8', '-encoding', 'UTF-8', '-cp', $androidJar, '-d', $classes, $source) 2>&1
$compilerExit = $LASTEXITCODE
$compilerOutput | ForEach-Object { Write-Output $_ }
if ($compilerExit -ne 0 -or ($compilerOutput -match 'An exception has occurred in the compiler')) {
    throw "javac failed or reported an internal compiler exception (exit $compilerExit)."
}
& jar @('--create', '--file', $classJar, '-C', $classes, 'dev/usbmic/inspector/shell/ShellAudioProbe.class')
if ($LASTEXITCODE -ne 0) { throw "jar failed: $LASTEXITCODE" }
& $d8 @('--lib', $androidJar, '--min-api', '31', '--output', $dexJar, $classJar)
if ($LASTEXITCODE -ne 0) { throw "D8 failed: $LASTEXITCODE" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($dexJar)
try {
    $dex = $archive.GetEntry('classes.dex')
    if ($null -eq $dex -or $dex.Length -le 0) { throw 'Output contains no classes.dex.' }
    Write-Output "Built read-only shell probe; classes.dex bytes=$($dex.Length)"
} finally {
    $archive.Dispose()
}
$artifact = Get-Item -LiteralPath $dexJar
$digest = Get-FileHash -LiteralPath $dexJar -Algorithm SHA256
Write-Output "Artifact=$($artifact.FullName); bytes=$($artifact.Length)"
Write-Output "SHA256=$($digest.Hash)"
