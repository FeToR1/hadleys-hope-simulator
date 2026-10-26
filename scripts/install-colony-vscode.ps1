param([switch]$Install)
$ErrorActionPreference = 'Stop'
$colonyRoot = Split-Path -Parent $PSScriptRoot
$extensionRoot = Join-Path $colonyRoot 'tools/vscode-colony'
$package = Get-Content -LiteralPath (Join-Path $extensionRoot 'package.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$outputRoot = Join-Path $colonyRoot 'results'
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$vsixPath = Join-Path $outputRoot "colony-language-$($package.version).vsix"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$file = [System.IO.File]::Open($vsixPath, [System.IO.FileMode]::Create)
$archive = [System.IO.Compression.ZipArchive]::new($file, [System.IO.Compression.ZipArchiveMode]::Create)
function Add-TextEntry([string]$Name, [string]$Text) {
    $entry = $archive.CreateEntry($Name)
    $writer = [System.IO.StreamWriter]::new($entry.Open(), [System.Text.UTF8Encoding]::new($false))
    try { $writer.Write($Text) } finally { $writer.Dispose() }
}
try {
    Add-TextEntry '[Content_Types].xml' @'
<?xml version="1.0" encoding="utf-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="json" ContentType="application/json"/><Default Extension="md" ContentType="text/markdown"/><Default Extension="vsixmanifest" ContentType="text/xml"/></Types>
'@
    Add-TextEntry 'extension.vsixmanifest' @"
<?xml version="1.0" encoding="utf-8"?>
<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011"><Metadata><Identity Language="en-US" Id="colony-language" Version="$($package.version)" Publisher="klyaksa"/><DisplayName>Colony Language</DisplayName><Description xml:space="preserve">Colony syntax highlighting</Description><Properties><Property Id="Microsoft.VisualStudio.Code.Engine" Value="^1.85.0"/><Property Id="Microsoft.VisualStudio.Code.ExtensionDependencies" Value=""/><Property Id="Microsoft.VisualStudio.Code.ExtensionPack" Value=""/></Properties></Metadata><Installation><InstallationTarget Id="Microsoft.VisualStudio.Code"/></Installation><Dependencies/><Assets><Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true"/></Assets></PackageManifest>
"@
    foreach ($relative in @('package.json', 'language-configuration.json', 'syntaxes/colony.tmLanguage.json', 'README.md')) {
        Add-TextEntry "extension/$relative" ([System.IO.File]::ReadAllText((Join-Path $extensionRoot $relative)))
    }
} finally { $archive.Dispose(); $file.Dispose() }
Write-Output "VSIX: $vsixPath"
if ($Install) {
    & code --install-extension $vsixPath --force
    if ($LASTEXITCODE -ne 0) { throw "VS Code installation failed: $LASTEXITCODE" }
}
