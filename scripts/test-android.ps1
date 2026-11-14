param(
    [string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'D:\Android\Sdk' }),
    [string]$Jdk = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21' }),
    [string]$Device = 'emulator-5580'
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$env:JAVA_HOME = $Jdk
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$tools = Join-Path $sdk 'build-tools\36.0.0'
$platform = Join-Path $sdk 'platforms\android-33\android.jar'
$testRoot = Join-Path $project 'build\android-test'
foreach ($name in @('classes','dex')) { New-Item -ItemType Directory -Path (Join-Path $testRoot $name) -Force | Out-Null }
& (Join-Path $tools 'aapt.exe') package -f -M (Join-Path $project 'tests\android\AndroidManifest.xml') -I $platform -F (Join-Path $testRoot 'unsigned.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test resource packaging failed' }
& (Join-Path $env:JAVA_HOME 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$platform;$(Join-Path $tools 'core-lambda-stubs.jar')" -classpath (Join-Path $project 'build\app-classes.jar') -d (Join-Path $testRoot 'classes') (Join-Path $project 'tests\android\SmokeInstrumentation.java')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& (Join-Path $env:JAVA_HOME 'bin\jar.exe') cf (Join-Path $testRoot 'tests.jar') -C (Join-Path $testRoot 'classes') .
& (Join-Path $tools 'd8.bat') --min-api 21 --lib $platform --classpath (Join-Path $project 'build\app-classes.jar') --output (Join-Path $testRoot 'dex') (Join-Path $testRoot 'tests.jar')
if ($LASTEXITCODE -ne 0) { throw 'Test dex compilation failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open((Join-Path $testRoot 'unsigned.apk'), [IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $testRoot 'dex\classes.dex'), 'classes.dex') | Out-Null } finally { $zip.Dispose() }
& (Join-Path $tools 'zipalign.exe') -f 4 (Join-Path $testRoot 'unsigned.apk') (Join-Path $testRoot 'aligned.apk')
& (Join-Path $tools 'apksigner.bat') sign --ks (Join-Path $project 'build\signing\local.keystore') --ks-key-alias p3print --ks-pass pass:android --key-pass pass:android --out (Join-Path $testRoot 'tests.apk') (Join-Path $testRoot 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test signing failed' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
& $adb -s $Device install -r (Join-Path $testRoot 'tests.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test installation failed' }
$instrumentation = & $adb -s $Device shell am instrument -w 'cn.paperstudio.p3print.test/cn.paperstudio.p3print.SmokeInstrumentation'
$instrumentation | Write-Output
if (($instrumentation -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1' -or ($instrumentation -join "`n") -notmatch '"result":"PASS"') { throw 'Android instrumentation did not complete successfully' }
& $adb -s $Device pull '/sdcard/Android/data/cn.paperstudio.p3print/files/android-results.json' (Join-Path $project 'qa\android-results.json')
$result = Get-Content -LiteralPath (Join-Path $project 'qa\android-results.json') -Raw | ConvertFrom-Json
if ($result.result -ne 'PASS') { throw "Android tests failed: $($result.error)" }
& $adb -s $Device pull '/sdcard/Android/data/cn.paperstudio.p3print/files/native-math-capture.png' (Join-Path $project 'qa\screenshots\native-math-capture.png')
& $adb -s $Device pull '/sdcard/Android/data/cn.paperstudio.p3print/files/pdf-page.png' (Join-Path $project 'qa\screenshots\pdf-page.png')
