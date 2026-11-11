$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repo '.test-classes'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& javac -encoding UTF-8 -d $output (Join-Path $repo 'app/src/main/java/dev/usbmic/inspector/UsbCapturePolicy.java') (Join-Path $PSScriptRoot 'UsbCapturePolicyTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $output UsbCapturePolicyTest
exit $LASTEXITCODE
