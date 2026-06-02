param(
    [string]$Serial = "emulator-5554",
    [string]$Sdk = "D:\Android Studio\SDK\Sdk",
    [int]$Port = 18089,
    [string]$OutputPath = ".\artifacts\emulator\webview-cookie-smoke.json"
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $Sdk "platform-tools\adb.exe"
$packageName = "com.aistudio.ereader.xynk"
$activity = "$packageName/com.example.MainActivity"
$service = "$packageName/com.example.web.LocalWebService"
$fixtureLog = ".\artifacts\emulator\webview-cookie-fixture.jsonl"
$loginDump = ".\artifacts\emulator\webview-cookie-login.xml"
$privateDump = ".\artifacts\emulator\webview-cookie-private.xml"

if (-not (Test-Path $adb)) {
    throw "ADB was not found at $adb"
}
if (-not (& $adb devices | Select-String -Pattern ([regex]::Escape($Serial) + "\s+device"))) {
    throw "Android device $Serial is not online"
}

New-Item -ItemType Directory -Force (Split-Path $OutputPath) | Out-Null
Remove-Item $fixtureLog -ErrorAction SilentlyContinue
& $adb -s $Serial reverse "tcp:$Port" "tcp:$Port" | Out-Null
$fixture = Start-Process python `
    -ArgumentList @(".\tools\webview-cookie-fixture.py", "$Port", $fixtureLog) `
    -WindowStyle Hidden `
    -PassThru

function Capture-Ui {
    param([string]$Path, [string]$Screenshot)
    & $adb -s $Serial shell uiautomator dump /sdcard/window.xml | Out-Null
    & $adb -s $Serial pull /sdcard/window.xml $Path | Out-Null
    & $adb -s $Serial exec-out screencap -p > $Screenshot
}

function Wait-Ui {
    param([string]$Pattern, [int]$TimeoutSeconds = 15)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 500
        & $adb -s $Serial shell uiautomator dump /sdcard/window.xml | Out-Null
        $xml = (& $adb -s $Serial shell cat /sdcard/window.xml) -join "`n"
        if ($xml -match [regex]::Escape($Pattern)) {
            return
        }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for UI text: $Pattern"
}

function Open-FixtureBrowser {
    param([int]$RowIndex)
    & $adb -s $Serial shell am force-stop $packageName | Out-Null
    & $adb -s $Serial shell am start -W -n $activity | Out-Null
    Wait-Ui "Online"
    # Library -> Explore -> Manage sources -> embedded browser.
    & $adb -s $Serial shell input tap 815 2230 | Out-Null
    Wait-Ui "000 COOKIE LOGIN FIXTURE"
    & $adb -s $Serial shell input tap 1005 340 | Out-Null
    Wait-Ui "Nguon online"
    $browserButtonY = 390 + (235 * $RowIndex)
    & $adb -s $Serial shell input tap 850 $browserButtonY | Out-Null
    Wait-Ui "Cookie"
}

function Post-Json {
    param([string]$Path, [hashtable]$Body)
    $json = $Body | ConvertTo-Json -Compress
    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "$script:baseUrl$Path" `
        -WebSession $script:webSession `
        -ContentType "application/json; charset=utf-8" `
        -Body ([Text.Encoding]::UTF8.GetBytes($json)) `
        -TimeoutSec 30
    if (-not $response.isSuccess) {
        throw "$Path failed: $($response.errorMsg)"
    }
    return $response
}

function Start-LocalApi {
    foreach ($webPort in 1122..1132) {
        & $adb -s $Serial forward "tcp:$webPort" "tcp:$webPort" | Out-Null
    }
    & $adb -s $Serial shell run-as $packageName am start-foreground-service --user 0 -n $service | Out-Null
    $script:webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $deadline = (Get-Date).AddSeconds(20)
    do {
        foreach ($webPort in 1122..1132) {
            try {
                $candidate = "http://127.0.0.1:$webPort"
                $bootstrap = Invoke-RestMethod -Method Get -Uri "$candidate/bootstrap" -WebSession $script:webSession -TimeoutSec 2
                if ($bootstrap.isSuccess) {
                    $script:baseUrl = $candidate
                    return
                }
            } catch {
                # Service startup is asynchronous.
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Local web service did not start."
}

try {
    Start-Sleep -Milliseconds 600
    Start-LocalApi
    Post-Json "/api/v1/vbook/extensions/install" @{ url = "http://127.0.0.1:$Port/login-plugin.zip" } | Out-Null
    Post-Json "/api/v1/vbook/extensions/install" @{ url = "http://127.0.0.1:$Port/private-plugin.zip" } | Out-Null
    Open-FixtureBrowser 0
    Start-Sleep -Seconds 3
    Capture-Ui $loginDump ".\artifacts\emulator\webview-cookie-login.png"
    $loginXml = Get-Content $loginDump -Raw -Encoding UTF8
    if ($loginXml -notmatch "Cookie login fixture") {
        throw "Embedded browser did not render the login fixture."
    }

    Open-FixtureBrowser 1
    Start-Sleep -Seconds 3
    Capture-Ui $privateDump ".\artifacts\emulator\webview-cookie-private.png"
    $privateXml = Get-Content $privateDump -Raw -Encoding UTF8
    $requests = @(Get-Content $fixtureLog -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json })
    $privateRequest = @($requests | Where-Object { $_.path -like "/private*" } | Select-Object -Last 1)
    $cookiePersisted = $privateRequest.Count -eq 1 -and $privateRequest[0].cookie -match "sid=browser-session"
    if (-not $cookiePersisted -or $privateXml -notmatch "PRIVATE COOKIE OK") {
        throw "Cookie did not survive force-stop and private page reload."
    }

    & $adb -s $Serial shell input tap 995 2330 | Out-Null
    Start-Sleep -Seconds 3
    Capture-Ui ".\artifacts\emulator\webview-cookie-cleared.xml" ".\artifacts\emulator\webview-cookie-cleared.png"
    $clearedXml = Get-Content ".\artifacts\emulator\webview-cookie-cleared.xml" -Raw -Encoding UTF8
    $requests = @(Get-Content $fixtureLog -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json })
    $clearedRequest = @($requests | Where-Object { $_.path -like "/private*" } | Select-Object -Last 1)
    $cookieCleared = $clearedRequest.Count -eq 1 -and $clearedRequest[0].cookie -notmatch "sid=browser-session"
    if (-not $cookieCleared -or $clearedXml -notmatch "COOKIE MISSING") {
        throw "Clear-site action did not remove the browser cookie."
    }

    $result = [pscustomobject]@{
        activity = "com.example.crawler.WebViewLoginActivity"
        loginRendered = $loginXml -match "Cookie login fixture"
        privateRenderedAfterRestart = $privateXml -match "PRIVATE COOKIE OK"
        cookiePersistedAfterRestart = $cookiePersisted
        privateRequestCookie = $privateRequest[0].cookie
        cookieClearedFromBrowserAndCrawlerStore = $cookieCleared
        loginScreenshot = (Resolve-Path ".\artifacts\emulator\webview-cookie-login.png").Path
        privateScreenshot = (Resolve-Path ".\artifacts\emulator\webview-cookie-private.png").Path
        clearedScreenshot = (Resolve-Path ".\artifacts\emulator\webview-cookie-cleared.png").Path
    }
    $result | ConvertTo-Json -Depth 4 | Set-Content $OutputPath -Encoding UTF8
    $result | ConvertTo-Json -Depth 4
} finally {
    try {
        Start-LocalApi
        Post-Json "/api/v1/vbook/extensions/delete" @{ sourceId = "000 COOKIE LOGIN FIXTURE-Codex" } | Out-Null
        Post-Json "/api/v1/vbook/extensions/delete" @{ sourceId = "001 COOKIE PRIVATE FIXTURE-Codex" } | Out-Null
    } catch {
        Write-Warning "Could not remove fixture extensions: $($_.Exception.Message)"
    }
    if (-not $fixture.HasExited) {
        Stop-Process -Id $fixture.Id -Force
    }
}
