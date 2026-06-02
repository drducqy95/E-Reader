param(
    [string]$RepositoryUrl = "https://raw.githubusercontent.com/duongden/vbook/main/plugin.json",
    [string]$SourceName = "vnexpress",
    [string]$Serial = "emulator-5554",
    [string]$Sdk = "D:\Android Studio\SDK\Sdk",
    [int]$FirstPort = 1122,
    [int]$LastPort = 1132,
    [int]$TimeoutSeconds = 300
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
    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "$script:baseUrl$Path" `
        -WebSession $script:webSession `
        -ContentType "application/json; charset=utf-8" `
        -Body ($Body | ConvertTo-Json -Compress -Depth 8) `
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

function Wait-ForDownload {
    param([int]$BookId, [string]$WorkId)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $snapshot = Get-Json "/api/v1/vbook/downloads?bookId=$BookId"
        $work = @($snapshot.data) | Where-Object { $_.id -eq $WorkId } | Select-Object -First 1
        if ($null -ne $work) {
            if ($work.state -eq "SUCCEEDED") {
                return $work
            }
            if ($work.state -in @("FAILED", "CANCELLED")) {
                throw "VBook download $WorkId ended in state $($work.state): $($work.error)"
            }
        }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for VBook download $WorkId"
}

$devices = & $adb devices
if (-not ($devices -match [regex]::Escape($Serial) + "\s+device")) {
    throw "Android device $Serial is not online"
}

foreach ($port in $FirstPort..$LastPort) {
    & $adb -s $Serial forward "tcp:$port" "tcp:$port" | Out-Null
}

# The debug APK is debuggable, so run-as can start the non-exported local service
# under the application uid without weakening the release manifest.
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
$extension = @($install.data) |
    Where-Object { $_.name -eq $SourceName } |
    Select-Object -First 1
if ($null -eq $extension) {
    throw "Extension '$SourceName' was not installed from $RepositoryUrl"
}

$homeResponse = Post-Json "/api/v1/vbook/home" @{ sourceId = $extension.id }
$articles = $null
$selectedTab = $null
foreach ($tab in @($homeResponse.data)) {
    $list = Post-Json "/api/v1/vbook/list" @{
        sourceId = $extension.id
        script = $tab.script
        input = $tab.input
        page = "1"
    }
    if (@($list.data).Count -gt 0) {
        $selectedTab = $tab
        $articles = @($list.data)
        break
    }
}
if ($null -eq $articles -or $articles.Count -eq 0) {
    throw "Extension '$SourceName' returned no live articles from its home tabs"
}

$onlineArticle = $articles[0]
$onlineDetail = Post-Json "/api/v1/vbook/book" @{ sourceId = $extension.id; url = $onlineArticle.url }
$onlineBook = Post-Json "/api/v1/vbook/read" @{ sourceId = $extension.id; url = $onlineArticle.url }
$onlineContent = Get-Json "/getBookContent?id=$($onlineBook.data.id)&index=0"
if (([string]$onlineContent.data).Length -lt 120) {
    throw "Online article content was unexpectedly short"
}

$downloadArticle = if ($articles.Count -gt 1) { $articles[1] } else { $articles[0] }
$download = Post-Json "/api/v1/vbook/download" @{ sourceId = $extension.id; url = $downloadArticle.url }
$completed = Wait-ForDownload ([int]$download.data.bookId) ([string]$download.data.workId)
$offlineContent = Get-Json "/getBookContent?id=$($download.data.bookId)&index=0"
if (([string]$offlineContent.data).Length -lt 120) {
    throw "Downloaded article content was unexpectedly short"
}

[pscustomobject]@{
    repository = $RepositoryUrl
    source = $extension.name
    sourceId = $extension.id
    baseUrl = $script:baseUrl
    homeTabs = @($homeResponse.data).Count
    selectedTab = $selectedTab.title
    listedArticles = $articles.Count
    onlineArticle = $onlineDetail.data.name
    onlineChars = ([string]$onlineContent.data).Length
    downloadedArticle = $downloadArticle.name
    downloadState = $completed.state
    downloadedChapters = $completed.downloadedChapters
    offlineChars = ([string]$offlineContent.data).Length
} | ConvertTo-Json -Depth 5
