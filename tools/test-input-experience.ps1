[CmdletBinding()]
param([string]$Serial, [string]$TestClass, [string]$ApkPath)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
. (Join-Path $PSScriptRoot 'lib/TestApkArtifact.ps1')
$sdkSetting = Get-Content (Join-Path $repositoryRoot "local.properties") |
    Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
if (-not $sdkSetting) { throw "Configure sdk.dir in local.properties before running device tests." }
$sdkRoot = $sdkSetting.Substring($sdkSetting.IndexOf('=') + 1).Replace('\:', ':').Replace('\\', '\')
$adb = Join-Path $sdkRoot "platform-tools/adb.exe"

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = @(& $adb -s $Serial @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($Arguments[0])" }
    return $output
}

$devices = @(& $adb devices | Select-String '^(\S+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
if ($LASTEXITCODE -ne 0) { throw "Unable to list ADB devices." }
if (-not $Serial) {
    if ($devices.Count -ne 1) { throw "Connect one device or provide -Serial." }
    $Serial = $devices[0]
}
if ($Serial -notin $devices) { throw "The selected device is not connected and authorized." }
$abi = @(Invoke-Adb @("shell", "getprop", "ro.product.cpu.abi"))[0].Trim()
if ($abi -notin @("arm64-v8a", "armeabi-v7a", "x86_64")) { throw "Unsupported test ABI." }

Push-Location $repositoryRoot
try {
    $tasks = @(':app:assembleDebugAndroidTest')
    if (-not $ApkPath) { $tasks += ':app:assembleDebug' }
    & ./gradlew.bat @tasks -PrequireRime=true --no-parallel "-Pandroid.injected.build.abi=$abi"
    if ($LASTEXITCODE -ne 0) { throw "Device test APK build failed." }
    $testDirectory = Join-Path $repositoryRoot "app/build/intermediates/apk/androidTest/debug"
    $testMetadata = Get-Content (Join-Path $testDirectory "output-metadata.json") -Raw | ConvertFrom-Json
    if (@($testMetadata.elements).Count -ne 1) { throw "Unexpected test APK outputs." }
    $selectedApk = if ($ApkPath) { (Resolve-Path -LiteralPath $ApkPath).Path }
        else { (Resolve-TestApkArtifact -RepositoryRoot $repositoryRoot -Abi $abi).Path }
    $expectedHash = (Get-FileHash -LiteralPath $selectedApk -Algorithm SHA256).Hash.ToLowerInvariant()
    Invoke-Adb @("install", "-r", "-t", $selectedApk)
    Invoke-Adb @("install", "-r", "-t", (Join-Path $testDirectory $testMetadata.elements[0].outputFile))
    $installedPaths = @(Invoke-Adb @('shell', 'pm', 'path', 'dev.zeroinput.ime.debug'))
    if ($installedPaths.Count -ne 1 -or $installedPaths[0] -notmatch '^package:(/data/app/[^\s]+/base\.apk)$') {
        throw 'Expected one installed base APK for verification.'
    }
    $installedPath = $Matches[1]
    $hashOutput = (Invoke-Adb @('shell', 'sha256sum', $installedPath)) -join ''
    if ($hashOutput -notmatch '^([a-fA-F0-9]{64})\s' -or $Matches[1].ToLowerInvariant() -ne $expectedHash) {
        throw 'Installed app differs from the APK selected for testing.'
    }
    Write-Output "Installed APK SHA-256 verified: $expectedHash"

    $originalMethod = @(Invoke-Adb @("shell", "settings", "get", "secure", "default_input_method"))[0].Trim()
    $changedMethod = $false
    try {
        if ($originalMethod -like 'dev.zeroinput.ime*') {
            $alternate = Invoke-Adb @("shell", "ime", "list", "-s") |
                Where-Object { $_ -and $_ -notlike 'dev.zeroinput.ime*' -and $_ -notmatch 'VoiceInput' } |
                Select-Object -First 1
            if (-not $alternate) { throw "Enable a system keyboard so tests can isolate the native runtime." }
            Invoke-Adb @("shell", "ime", "set", $alternate.Trim())
            $changedMethod = $true
        }
        $instrumentArguments = @("shell", "am", "instrument", "-w")
        if ($TestClass) { $instrumentArguments += @("-e", "class", $TestClass) }
        $instrumentArguments += "dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner"
        $result = Invoke-Adb $instrumentArguments
        $result | Write-Output
        if (($result -join "`n") -notmatch 'OK \(\d+ tests?\)' -or ($result -join "`n") -match 'FAILURES!!!') {
            throw "Device regression tests failed."
        }
        $finalHash = (Invoke-Adb @('shell', 'sha256sum', $installedPath)) -join ''
        if ($finalHash -notmatch '^([a-fA-F0-9]{64})\s' -or $Matches[1].ToLowerInvariant() -ne $expectedHash) {
            throw 'Installed APK changed during device tests.'
        }
        @{
            apk = $selectedApk; sha256 = $expectedHash; serial = $Serial; tests = $TestClass
            passed = $true; verifiedAtUtc = [DateTime]::UtcNow.ToString('o')
        } | ConvertTo-Json | Set-Content (Join-Path $repositoryRoot 'build/device-apk-verification.json') -Encoding utf8
    } finally {
        if ($changedMethod) { Invoke-Adb @("shell", "ime", "set", $originalMethod) }
    }
} finally {
    Pop-Location
}
