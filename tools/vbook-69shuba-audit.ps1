param(
    [string]$CurrentZipUrl = "https://raw.githubusercontent.com/duongden/vbook/main/FixedDomain/69shu/plugin.zip",
    [string]$LegacyZipUrl = "https://raw.githubusercontent.com/duongden/vbook/1c61192d52187b760d615a96a8001077e492c83a/FixedDomain/69shu/plugin.zip",
    [string]$SourceUrl = "https://www.69shuba.com",
    [string]$OutputPath = ".\artifacts\emulator\vbook-69shuba-audit.json"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Inspect-PluginZip {
    param([string]$Url)
    $temporary = Join-Path $env:TEMP "vbook-69shu-$([Guid]::NewGuid().ToString('N')).zip"
    try {
        Invoke-WebRequest -Uri $Url -OutFile $temporary -UseBasicParsing -TimeoutSec 40
        $archive = [IO.Compression.ZipFile]::OpenRead($temporary)
        try {
            $entry = $archive.Entries | Where-Object { $_.FullName -eq "plugin.json" } | Select-Object -First 1
            if ($null -eq $entry) { throw "plugin.json not found" }
            $reader = New-Object IO.StreamReader($entry.Open())
            try {
                $plugin = $reader.ReadToEnd() | ConvertFrom-Json
            } finally {
                $reader.Dispose()
            }
        } finally {
            $archive.Dispose()
        }
        $file = Get-Item -LiteralPath $temporary
        [pscustomobject]@{
            url = $Url
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporary).Hash
            bytes = $file.Length
            name = $plugin.metadata.name
            version = $plugin.metadata.version
            encrypted = [bool]$plugin.metadata.encrypt
            source = $plugin.metadata.source
        }
    } finally {
        if (Test-Path $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Probe-Host {
    param([string]$Url)
    try {
        $response = Invoke-WebRequest `
            -Uri $Url `
            -Headers @{ "User-Agent" = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36" } `
            -UseBasicParsing `
            -TimeoutSec 25
        [pscustomobject]@{
            url = $Url
            status = [int]$response.StatusCode
            server = [string]$response.Headers["Server"]
            chars = $response.Content.Length
        }
    } catch {
        [pscustomobject]@{
            url = $Url
            status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { "ERROR" }
            server = if ($_.Exception.Response) { [string]$_.Exception.Response.Headers["Server"] } else { "" }
            chars = 0
            error = $_.Exception.Message
        }
    }
}

$result = [pscustomobject]@{
    checkedAt = (Get-Date).ToString("o")
    currentPlugin = Inspect-PluginZip $CurrentZipUrl
    publicLegacyPlugin = Inspect-PluginZip $LegacyZipUrl
    hostProbe = Probe-Host $SourceUrl
    conclusion = "Current 69shuba extension is encrypted and requires a VBook-compatible decoder. The public legacy plugin can be inspected, but direct HTTP crawling is blocked when the host probe returns 403."
}

$directory = Split-Path -Parent $OutputPath
if ($directory) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
$json = $result | ConvertTo-Json -Depth 6
Set-Content -LiteralPath $OutputPath -Value $json -Encoding UTF8
$json
