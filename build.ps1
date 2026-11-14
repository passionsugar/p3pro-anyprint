param(
    [string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'D:\Android\Sdk' }),
    [string]$Jdk = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21' })
)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$build = Join-Path $project 'build'
$platform = Join-Path $Sdk 'platforms\android-33\android.jar'
$toolDirectory = Join-Path $Sdk 'build-tools\36.0.0'
$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$env:PATH"
function Check-Exit([string]$stage) { if ($LASTEXITCODE -ne 0) { throw "$stage failed with exit code $LASTEXITCODE" } }
foreach ($name in @('classes','generated','dex','package','signing')) { New-Item -ItemType Directory -Path (Join-Path $build $name) -Force | Out-Null }
New-Item -ItemType Directory -Path (Join-Path $project 'delivery') -Force | Out-Null
if (!(Test-Path -LiteralPath $platform) -or !(Test-Path -LiteralPath (Join-Path $toolDirectory 'aapt.exe'))) { throw 'Install Android SDK Platform 33 and Build Tools 36.0.0. Set ANDROID_HOME or pass -Sdk.' }
if (!(Test-Path -LiteralPath (Join-Path $Jdk 'bin\javac.exe'))) { throw 'Set JAVA_HOME to your JDK directory or pass -Jdk.' }
if (!(Test-Path -LiteralPath (Join-Path $project 'app\src\main\assets\vendor\quill.js'))) { throw 'Run npm ci and npm run vendor first.' }
if (!(Test-Path -LiteralPath (Join-Path $project 'app\src\main\jniLibs\armeabi-v7a\libserial_port.so'))) { throw 'Run scripts\extract-native.ps1 first.' }
$manifest = Join-Path $project 'app\src\main\AndroidManifest.xml'
$unsigned = Join-Path $build 'unsigned.apk'
& (Join-Path $toolDirectory 'aapt.exe') package -f -M $manifest -S (Join-Path $project 'app\src\main\res') -A (Join-Path $project 'app\src\main\assets') -I $platform -J (Join-Path $build 'generated') -F $unsigned --version-code 2 --version-name '1.0.1'
Check-Exit 'Android resource packaging'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $project 'app\src\main\java'),(Join-Path $build 'generated') -Filter '*.java' -Recurse -File | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
$sourceList = Join-Path $build 'sources.txt'
[IO.File]::WriteAllLines($sourceList, $sources, [Text.UTF8Encoding]::new($false))
& (Join-Path $Jdk 'bin\javac.exe') '-J-Dfile.encoding=UTF-8' '-J-Duser.language=en' -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$platform;$(Join-Path $toolDirectory 'core-lambda-stubs.jar')" -d (Join-Path $build 'classes') "@$sourceList"
Check-Exit 'Java compilation'
& (Join-Path $Jdk 'bin\jar.exe') cf (Join-Path $build 'app-classes.jar') -C (Join-Path $build 'classes') .
Check-Exit 'Class archive'
& (Join-Path $toolDirectory 'd8.bat') --min-api 21 --lib $platform --output (Join-Path $build 'dex') (Join-Path $build 'app-classes.jar')
Check-Exit 'DEX compilation'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $build 'dex\classes.dex'), 'classes.dex') | Out-Null
    $nativeRoot = Join-Path $project 'app\src\main\jniLibs'
    foreach ($file in Get-ChildItem -LiteralPath $nativeRoot -Recurse -File -Filter '*.so') {
        $entry = 'lib/' + $file.FullName.Substring($nativeRoot.Length + 1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $entry) | Out-Null
    }
} finally { $archive.Dispose() }
$aligned = Join-Path $build 'aligned.apk'
& (Join-Path $toolDirectory 'zipalign.exe') -f -p 4 $unsigned $aligned
Check-Exit 'APK alignment'
$key = Join-Path $build 'signing\local.keystore'
if (!(Test-Path -LiteralPath $key)) {
    & (Join-Path $Jdk 'bin\keytool.exe') -genkeypair -keystore $key -storepass android -keypass android -alias p3print -dname 'CN=P3 AnyPrint Local Build' -keyalg RSA -keysize 2048 -validity 10000 -storetype JKS
    Check-Exit 'Local signing key generation'
}
$output = Join-Path $project 'delivery\P3Pro-AnyPrint-1.0.1.apk'
& (Join-Path $toolDirectory 'apksigner.bat') sign --ks $key --ks-key-alias p3print --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --out $output $aligned
Check-Exit 'APK signing'
& (Join-Path $toolDirectory 'apksigner.bat') verify --verbose $output
Check-Exit 'APK signature verification'
$hash = Get-FileHash -LiteralPath $output -Algorithm SHA256
$hash | Format-List
[IO.File]::WriteAllText((Join-Path $project 'delivery\SHA256.txt'), "$($hash.Hash)  P3Pro-AnyPrint-1.0.1.apk`r`n", [Text.UTF8Encoding]::new($false))
Write-Output "Built $output"
