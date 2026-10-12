$ErrorActionPreference='Stop'
$waveRepo=Split-Path -Parent $PSScriptRoot
$waveBuild=Join-Path $env:TEMP 'CatchWave-public25-host-build'
$waveClasses=Join-Path $env:TEMP 'CatchWave-public25-interop-classes'
$waveJavaBin=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin'}else{Split-Path -Parent (Get-Command java.exe -ErrorAction Stop).Source}
[IO.Directory]::CreateDirectory($waveClasses) | Out-Null
& "$waveJavaBin\javac.exe" -d $waveClasses "$waveRepo\app\src\main\java\app\catchwave\LanProtocol.java" "$waveRepo\app\src\main\java\app\catchwave\LanTimeline.java" "$waveRepo\app\src\main\java\app\catchwave\LanFreshStream.java" "$waveRepo\tools\interop\WireClient.java"
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& dotnet build "$PSScriptRoot\CatchWave.Host\CatchWave.Host.csproj" -c Release "-p:BaseIntermediateOutputPath=$waveBuild\obj\" "-p:OutputPath=$waveBuild\bin\"
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& dotnet "$waveBuild\bin\CatchWave.Host.dll" --interop-test "$waveJavaBin\java.exe" $waveClasses
exit $LASTEXITCODE
