# Build a distributable folder (dist/) for YI Launcher.
# Usage: powershell -ExecutionPolicy Bypass -File .\package-release.ps1
# Output:
#   dist\app.jar             launcher jar
#   dist\lib\                JavaFX modules (module path)
#   dist\ext\                JNA jars (classpath)
#   dist\run.cmd             double click to run

#   dist\README-release.txt  release notes / checklist
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less
# scripts as ANSI, and non-ASCII text breaks the parser.
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-25' }

Write-Host '[1/4] mvn compile...'
& .\mvnw.cmd -o -q compile
if ($LASTEXITCODE -ne 0) { throw 'mvn compile failed' }

# 离线环境下 maven-clean / surefire 插件可能不在本地仓库，
# 所以不用 mvn package，直接用 JDK 的 jar 打 target\classes。
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\jar.exe'))) {
  $candidates = @(
    'C:\Program Files\Zulu\zulu-25',
    'C:\Program Files\Zulu\zulu-21',
    'C:\Program Files\Java\jdk-21',
    'C:\Program Files\Eclipse Adoptium\jdk-21'
  )
  foreach ($candidate in $candidates) {
    if (Test-Path (Join-Path $candidate 'bin\jar.exe')) { $javaHome = $candidate; break }
  }
}
if (-not $javaHome) { throw 'JAVA_HOME not set and no JDK found' }
$jarExe = Join-Path $javaHome 'bin\jar.exe'
Write-Host "    using JDK: $javaHome"
Write-Host '[2/4] preparing dist/...'
if (Test-Path dist) { Remove-Item dist -Recurse -Force }
New-Item dist, dist\lib, dist\ext -ItemType Directory | Out-Null
& $jarExe --create --file (Join-Path $root 'dist\app.jar') --main-class com.yilauncher.yi_launcher.LauncherApp -C target\classes .
if ($LASTEXITCODE -ne 0) { throw 'jar packaging failed' }

$m2 = Join-Path $env:USERPROFILE '.m2\repository'
$javafx = @(
  'org\openjfx\javafx-base\21.0.6\javafx-base-21.0.6-win.jar',
  'org\openjfx\javafx-graphics\21.0.6\javafx-graphics-21.0.6-win.jar',
  'org\openjfx\javafx-controls\21.0.6\javafx-controls-21.0.6-win.jar',
  'org\openjfx\javafx-fxml\21.0.6\javafx-fxml-21.0.6-win.jar'
)
foreach ($rel in $javafx) {
  $src = Join-Path $m2 $rel
  if (-not (Test-Path $src)) { throw "missing JavaFX dependency: $src" }
  Copy-Item $src (Join-Path $root ('dist\lib\' + (Split-Path $src -Leaf)))
}

$ext = @(
  'net\java\dev\jna\jna\5.14.0\jna-5.14.0.jar',
  'net\java\dev\jna\jna-platform\5.14.0\jna-platform-5.14.0.jar'
)
foreach ($rel in $ext) {
  $src = Join-Path $m2 $rel
  if (-not (Test-Path $src)) { throw "missing JNA dependency: $src" }
  Copy-Item $src (Join-Path $root ('dist\ext\' + (Split-Path $src -Leaf)))
}

Write-Host '[3/4] writing run.cmd...'
$cmdLines = @(
  '@echo off',
  'rem YI Launcher - requires Java 21 or newer (with JavaFX 21)',
  'setlocal',
  'set DIR=%~dp0',
  'where javaw >nul 2>nul',
  'if errorlevel 1 (',
  '  echo Java 21+ not found. Please install it first.',
  '  pause',
  '  exit /b 1',
  ')',
  'start "" javaw --module-path "%DIR%lib" --add-modules javafx.controls,javafx.fxml -cp "%DIR%app.jar;%DIR%ext\*" com.yilauncher.yi_launcher.LauncherApp',
  'endlocal'
)
Set-Content -Path (Join-Path $root 'dist\run.cmd') -Value ($cmdLines -join "`r`n") -Encoding OEM

Write-Host '[4/4] writing docs...'

if (Test-Path (Join-Path $root 'RELEASE.md')) {
  Copy-Item (Join-Path $root 'RELEASE.md') (Join-Path $root 'dist\README-release.txt')
}

Write-Host ''
Write-Host 'done. files in dist/:'
Get-ChildItem dist -Recurse | Select-Object FullName, Length | Format-Table -AutoSize | Out-String -Width 160
