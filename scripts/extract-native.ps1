param([string]$Apk = (Join-Path $PSScriptRoot '..\inputs\P3ProPrint.apk'))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$target = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\app\src\main\jniLibs'))
$zip = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($Apk))
try {
    foreach ($abi in @('armeabi-v7a','arm64-v8a','x86','x86_64')) {
        $directory = Join-Path $target $abi
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        foreach ($name in @('libserial_port.so','libmcu_power.so','libmmj_img_alg_core.so')) {
            $entry = $zip.GetEntry("lib/$abi/$name")
            if ($null -eq $entry) { throw "Missing native library $abi/$name" }
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $directory $name), $true)
        }
    }
} finally { $zip.Dispose() }
Write-Output 'Extracted the vendor JNI libraries from the supplied APK.'
