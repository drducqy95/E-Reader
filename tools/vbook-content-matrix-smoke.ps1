param(
    [string]$RepositoryUrl = "https://raw.githubusercontent.com/duongden/vbook/main/plugin.json",
    [string]$Serial = "emulator-5554",
    [string]$Sdk = "D:\Android Studio\SDK\Sdk",
    [int]$FirstPort = 1122,
    [int]$LastPort = 1132,
    [int]$TimeoutSeconds = 90,
    [int]$MaxTabs = 5,
    [int]$MinContentChars = 120,
    [string[]]$RequiredSourcePatterns = @(
        "*Full",
        "Tinh Linh",
        "*Qidian",
        "vnexpress"
    ),
    [string]$OutputPath = ".\artifacts\emulator\vbook-extension-content-matrix-final.json"
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $Sdk "platform-tools\adb.exe"
$packageName = "com.aistudio.ereader.xynk"
$serviceName = "$packageName/com.example.web.LocalWebService"

if (-not (Test-Path $adb)) {
    throw "ADB was not found at $adb"
}

function Assert-Success {
    param([object]$Response, [string]$Operation)
    if ($null -eq $Response -or -not $Response.isSuccess) {
        $message = if ($null -ne $Response) { $Response.errorMsg } else { "empty response" }
        throw "$Operation failed: $message"
    }
    return $Response
}

function Post-Json {
    param([string]$Path, [hashtable]$Body)
    $json = $Body | ConvertTo-Json -Compress -Depth 8
    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "$script:baseUrl$Path" `
        -WebSession $script:webSession `
        -ContentType "application/json; charset=utf-8" `
        -Body ([Text.Encoding]::UTF8.GetBytes($json)) `
        -TimeoutSec $TimeoutSeconds
    return Assert-Success $response "POST $Path"
}

function Get-Json {
    param([string]$Path)
    $response = Invoke-RestMethod `
        -Method Get `
        -Uri "$script:baseUrl$Path" `
        -WebSession $script:webSession `
        -TimeoutSec $TimeoutSeconds
    return Assert-Success $response "GET $Path"
}

$devices = & $adb devices
if (-not ($devices -match [regex]::Escape($Serial) + "\s+device")) {
    throw "Android device $Serial is not online"
}

foreach ($port in $FirstPort..$LastPort) {
    & $adb -s $Serial forward "tcp:$port" "tcp:$port" | Out-Null
}

# The debug APK is debuggable, so run-as can start the local-only service without
# exporting it from the release manifest.
& $adb -s $Serial shell run-as $packageName am start-foreground-service --user 0 -n $serviceName | Out-Null

$script:webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$script:baseUrl = $null
$deadline = (Get-Date).AddSeconds(20)
do {
    foreach ($port in $FirstPort..$LastPort) {
        try {
            $candidate = "http://127.0.0.1:$port"
            $bootstrap = Invoke-RestMethod -Method Get -Uri "$candidate/bootstrap" -WebSession $script:webSession -TimeoutSec 2
            if ($bootstrap.isSuccess) {
                $script:baseUrl = $candidate
                break
            }
        } catch {
            # Service startup is asynchronous; keep probing the bounded port range.
        }
    }
    if ($script:baseUrl) { break }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)

if (-not $script:baseUrl) {
    throw "Local web service did not start. Install a debug APK and verify run-as works for $packageName."
}

$install = Post-Json "/api/v1/vbook/extensions/install" @{ url = $RepositoryUrl }
$rows = @()

foreach ($extension in @($install.data)) {
    $stage = "home"
    $listed = 0
    $tabs = 0
    $chapters = 0
    $chars = 0
    $title = ""
    $note = ""
    try {
        $homeResponse = Post-Json "/api/v1/vbook/home" @{ sourceId = $extension.id }
        $tabs = @($homeResponse.data).Count
        $stage = "list"
        $books = $null
        foreach ($tab in @($homeResponse.data) | Select-Object -First $MaxTabs) {
            try {
                $list = Post-Json "/api/v1/vbook/list" @{
                    sourceId = $extension.id
                    script = $tab.script
                    input = $tab.input
                    page = "1"
                }
                if (@($list.data).Count -gt 0) {
                    $books = @($list.data)
                    break
                }
            } catch {
                $note = $_.Exception.Message
            }
        }
        if ($null -eq $books -or $books.Count -eq 0) {
            throw "no article/book in first $MaxTabs tabs$(if ($note) { ": $note" })"
        }

        $listed = $books.Count
        $stage = "detail/toc"
        $detail = Post-Json "/api/v1/vbook/book" @{ sourceId = $extension.id; url = $books[0].url }
        $title = [string]$detail.data.name
        $chapters = @($detail.data.chapters).Count
        $stage = "content"
        $book = Post-Json "/api/v1/vbook/read" @{ sourceId = $extension.id; url = $books[0].url }
        $content = Get-Json "/getBookContent?id=$($book.data.id)&index=0"
        $chars = ([string]$content.data).Length
        if ($chars -lt $MinContentChars) {
            throw "chapter content was unexpectedly short: $chars chars"
        }
        $stage = "PASS"
        $note = "tabs=$tabs, listed=$listed, chapters=$chapters"
    } catch {
        $note = "$stage`: $($_.Exception.Message)"
    }
    $rows += [pscustomobject]@{
        source = [string]$extension.name
        stage = $stage
        chars = $chars
        title = $title
        note = $note
    }
}

$requiredFailures = @(
    foreach ($sourcePattern in $RequiredSourcePatterns) {
        $row = $rows | Where-Object { $_.source -like $sourcePattern } | Select-Object -First 1
        if ($null -eq $row) {
            "$sourcePattern (not installed)"
        } elseif ($row.stage -ne "PASS") {
            "$sourcePattern ($($row.stage))"
        }
    }
)

$result = [pscustomobject]@{
    repository = $RepositoryUrl
    baseUrl = $script:baseUrl
    installed = $rows.Count
    passed = @($rows | Where-Object { $_.stage -eq "PASS" }).Count
    failed = @($rows | Where-Object { $_.stage -ne "PASS" }).Count
    requiredSourcePatterns = $RequiredSourcePatterns
    requiredPassed = $requiredFailures.Count -eq 0
    rows = $rows
}

$jsonResult = $result | ConvertTo-Json -Depth 8
$outputDirectory = Split-Path -Parent $OutputPath
if ($outputDirectory) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}
Set-Content -LiteralPath $OutputPath -Value $jsonResult -Encoding UTF8
$jsonResult

if ($requiredFailures.Count -gt 0) {
    throw "Required live VBook sources failed: $($requiredFailures -join ', ')"
}
