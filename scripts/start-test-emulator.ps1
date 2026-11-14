param(
    [string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'D:\Android\Sdk' }),
    [string]$Jdk = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21' })
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$env:ANDROID_AVD_HOME = Join-Path $project 'qa\avd'
$env:ANDROID_USER_HOME = Join-Path $project 'qa\android'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:TEMP = Join-Path $project 'tools\temp'
$env:TMP = $env:TEMP
$env:JAVA_HOME = $Jdk
$libraryDirectory = Join-Path $Sdk 'emulator\lib64'
$env:PATH = "$libraryDirectory;$libraryDirectory\gles_swiftshader;$libraryDirectory\vulkan;$env:PATH"
foreach ($directory in @($env:ANDROID_AVD_HOME, $env:ANDROID_USER_HOME, $env:TEMP)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_AVD_HOME 'P3Print_API28.ini'))) {
    if (!(Test-Path -LiteralPath (Join-Path $Sdk 'system-images\android-28\google_apis\x86\system.img'))) { throw 'Install system-images;android-28;google_apis;x86 with Android SDK Manager before starting the test emulator.' }
    'no' | & (Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat') create avd -n P3Print_API28 -k 'system-images;android-28;google_apis;x86' -p (Join-Path $env:ANDROID_AVD_HOME 'P3Print_API28.avd')
    if ($LASTEXITCODE -ne 0) { throw 'Creating the test AVD failed' }
}
Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public static class P3QuietEmulator { [DllImport("kernel32.dll")] public static extern uint SetErrorMode(uint mode); }'
[P3QuietEmulator]::SetErrorMode(0x8007) | Out-Null
$process = Start-Process -FilePath (Join-Path $Sdk 'emulator\emulator.exe') -ArgumentList @('-avd','P3Print_API28','-port','5580','-no-window','-no-audio','-no-snapshot','-no-boot-anim','-gpu','swiftshader','-skin','534x320','-dpi-device','160','-memory','1536','-no-metrics') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $project 'qa\emulator-out.log') -RedirectStandardError (Join-Path $project 'qa\emulator-error.log') -PassThru
Write-Output "P3 test emulator PID $($process.Id). AVD data, logs and temporary files are under $project."
