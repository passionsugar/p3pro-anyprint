param(
    [string]$Jdk = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21' }),
    [switch]$CompareOriginal
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$javac = Join-Path $Jdk 'bin\javac.exe'
$java = Join-Path $Jdk 'bin\java.exe'
$output = Join-Path $project 'build\protocol-test'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$sources = @((Join-Path $project 'tests\ProtocolDifferential.java'), (Join-Path $project 'app\src\main\java\cn\paperstudio\p3print\Protocol.java'))
if ($CompareOriginal) {
    $originalTools = Join-Path $project 'research\p3\sources\com\paperang\tools\Tools.java'
    $originalCommands = Join-Path $project 'research\p3\sources\com\paperang\printer\data\PrinterCmd.java'
    if (!(Test-Path -LiteralPath $originalTools) -or !(Test-Path -LiteralPath $originalCommands)) { throw 'Place the local JADX output at research\p3, including Tools.java and PrinterCmd.java, to compare the original APK implementation.' }
    $sources += $originalTools, $originalCommands
}
& $javac -encoding UTF-8 -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Differential test compilation failed' }
$arguments = @('-cp', $output, 'ProtocolDifferential')
if ($CompareOriginal) { $arguments += '--original' }
& $java @arguments
if ($LASTEXITCODE -ne 0) { throw 'Differential protocol verification failed' }
