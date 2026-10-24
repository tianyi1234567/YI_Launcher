# Build a single-file Windows exe for YI Launcher.
#   dist\YI_Launcher.exe        <-- one file to hand out (JavaFX + JNA inside)
# The exe checks for Java 21+ on the target machine and offers a download page.
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less scripts as ANSI.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

# Draws a rounded gradient tile with the "yi" glyph and saves it as a PNG-based .ico.
function New-AppIcon([string]$path) {
    Add-Type -AssemblyName System.Drawing
    $size = 256
    $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear([System.Drawing.Color]::Transparent)

    $rect = New-Object System.Drawing.RectangleF 0, 0, $size, $size
    $shape = New-Object System.Drawing.Drawing2D.GraphicsPath
    $r = 58
    $shape.AddArc(0, 0, $r * 2, $r * 2, 180, 90)
    $shape.AddArc($size - $r * 2, 0, $r * 2, $r * 2, 270, 90)
    $shape.AddArc($size - $r * 2, $size - $r * 2, $r * 2, $r * 2, 0, 90)
    $shape.AddArc(0, $size - $r * 2, $r * 2, $r * 2, 90, 90)
    $shape.CloseFigure()

    $c1 = [System.Drawing.Color]::FromArgb(255, 108, 124, 255)
    $c2 = [System.Drawing.Color]::FromArgb(255, 169, 139, 224)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, $c1, $c2, ([single]45)
    $g.FillPath($brush, $shape)

    $glyph = [string][char]0x8863
    $font = New-Object System.Drawing.Font 'Microsoft YaHei', ([single]148), ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
    $format = New-Object System.Drawing.StringFormat
    $format.Alignment = [System.Drawing.StringAlignment]::Center
    $format.LineAlignment = [System.Drawing.StringAlignment]::Center
    $g.DrawString($glyph, $font, $white, $rect, $format)

    $pngPath = [System.IO.Path]::ChangeExtension($path, '.png')
    $bmp.Save($pngPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
    $brush.Dispose()
    $shape.Dispose()
    $font.Dispose()
    $white.Dispose()

    $pngBytes = [System.IO.File]::ReadAllBytes($pngPath)
    $stream = New-Object System.IO.MemoryStream
    $writer = New-Object System.IO.BinaryWriter $stream
    $writer.Write([UInt16]0)          # reserved
    $writer.Write([UInt16]1)          # type = icon
    $writer.Write([UInt16]1)          # one image
    $writer.Write([Byte]0)            # width  (0 = 256)
    $writer.Write([Byte]0)            # height (0 = 256)
    $writer.Write([Byte]0)            # palette
    $writer.Write([Byte]0)            # reserved
    $writer.Write([UInt16]1)          # planes
    $writer.Write([UInt16]32)         # bpp
    $writer.Write([UInt32]$pngBytes.Length)
    $writer.Write([UInt32]22)         # data offset
    $writer.Write($pngBytes)
    $writer.Flush()
    [System.IO.File]::WriteAllBytes($path, $stream.ToArray())
    $writer.Dispose()
    $stream.Dispose()
    Remove-Item $pngPath -Force
}

# Copies a jar while dropping entries whose path matches the given pattern
# (used to keep only the Windows JNA native library).
function New-SlimJna([string]$source, [string]$destination) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $inZip = [System.IO.Compression.ZipFile]::OpenRead($source)
    $outStream = [System.IO.File]::Open($destination, [System.IO.FileMode]::Create)
    $outZip = New-Object System.IO.Compression.ZipArchive($outStream, [System.IO.Compression.ZipArchiveMode]::Create)
    foreach ($entry in $inZip.Entries) {
        if ($entry.FullName -match '^com/sun/jna/(linux|sunos|freebsd|openbsd)-') { continue }
        $copy = $outZip.CreateEntry($entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
        $copy.LastWriteTime = $entry.LastWriteTime
        if ($entry.Length -gt 0) {
            $from = $entry.Open()
            $to = $copy.Open()
            $from.CopyTo($to)
            $to.Dispose()
            $from.Dispose()
        }
    }
    $outZip.Dispose()
    $outStream.Dispose()
    $inZip.Dispose()
}
$exe = Join-Path $root 'dist\YI_Launcher.exe'
$buildDir = Join-Path $root 'build'

Write-Host '[1/5] building dist\ (mvn compile + copy runtimes)...'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'package-release.ps1')
if ($LASTEXITCODE -ne 0) { throw 'package-release.ps1 failed' }

Write-Host '[2/5] packing payload...'
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
$payload = Join-Path $buildDir 'payload'
New-Item $payload -ItemType Directory | Out-Null
New-Item (Join-Path $payload 'lib') -ItemType Directory | Out-Null
New-Item (Join-Path $payload 'ext') -ItemType Directory | Out-Null
Copy-Item (Join-Path $root 'dist\app.jar') (Join-Path $payload 'app.jar')
Copy-Item (Join-Path $root 'dist\lib\*.jar') (Join-Path $payload 'lib')
Copy-Item (Join-Path $root 'dist\ext\*.jar') (Join-Path $payload 'ext')
# Keep only the Windows JNA native library: the launcher ships for Windows,
# the linux/sunos/freebsd/openbsd .so files would only bloat the package.
$jnaJar = Join-Path $payload 'ext\jna-5.14.0.jar'
if (Test-Path $jnaJar) {
    $before = (Get-Item $jnaJar).Length
    $slim = Join-Path $buildDir 'jna-slim.jar'
    New-SlimJna $jnaJar $slim
    Move-Item $slim $jnaJar -Force
    Write-Host ('    jna slim: {0:N2} MB -> {1:N2} MB' -f ($before / 1MB), ((Get-Item $jnaJar).Length / 1MB))
}
$zip = Join-Path $buildDir 'payload.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($payload, $zip,
    [System.IO.Compression.CompressionLevel]::Optimal, $false)
$payloadSize = (Get-Item $zip).Length

Write-Host '[3/5] drawing icon...'
$ico = Join-Path $buildDir 'app.ico'
$haveIcon = $false
try {
    New-AppIcon $ico
    $haveIcon = $true
} catch {
    Write-Host ('    icon skipped: ' + $_.Exception.Message)
}

Write-Host '[4/5] compiling launcher...'
$csc = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { $csc = 'C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (-not (Test-Path $csc)) { throw 'csc.exe (.NET Framework) not found' }
if (Test-Path $exe) { Remove-Item $exe -Force }

$cscArgs = @(
    '/nologo',
    '/target:winexe',
    '/platform:anycpu',
    '/optimize+',
    '/codepage:65001',
    ('/out:' + $exe),
    ('/resource:' + $zip + ',payload.zip'),
    ('/win32manifest:' + (Join-Path $root 'launcher\Launcher.manifest')),
    '/r:System.dll',
    '/r:System.Windows.Forms.dll',
    '/r:System.IO.Compression.dll',
    '/r:System.IO.Compression.FileSystem.dll'
)
if ($haveIcon) { $cscArgs += ('/win32icon:' + $ico) }
$cscArgs += (Join-Path $root 'launcher\Launcher.cs')
& $csc $cscArgs
if ($LASTEXITCODE -ne 0) { throw 'csc failed' }

Write-Host '[5/5] done.'
$size = (Get-Item $exe).Length
Write-Host ''
Write-Host ('payload.zip : {0:N2} MB' -f ($payloadSize / 1MB))
Write-Host ('exe         : {0:N2} MB  ->  {1}' -f ($size / 1MB), $exe)
Write-Host ''
Write-Host 'dist\ layout (the single exe is enough to ship):'
Get-ChildItem (Join-Path $root 'dist') | Select-Object Name, Length | Format-Table -AutoSize | Out-String -Width 120 | Write-Host
