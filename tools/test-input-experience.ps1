[CmdletBinding()]
param([string]$Serial, [string]$TestClass, [string]$ApkPath, [string]$Jdk17Path)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
. (Join-Path $PSScriptRoot 'lib/TestApkArtifact.ps1')
. (Join-Path $PSScriptRoot 'lib/JavaToolchain.ps1')
. (Join-Path $PSScriptRoot 'lib/InstrumentationResult.ps1')
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

function Assert-InstalledApk {
    param([string]$PackageName, [string]$ExpectedHash)

    $paths = @(Invoke-Adb @('shell', 'pm', 'path', $PackageName))
    if ($paths.Count -ne 1 -or $paths[0] -notmatch '^package:(/data/app/[^\s]+/base\.apk)$') {
        throw "Expected one installed base APK for $PackageName."
    }
    $hashOutput = @(Invoke-Adb @('shell', 'sha256sum', $Matches[1]))
    if ($hashOutput.Count -ne 1 -or $hashOutput[0] -notmatch '^([a-fA-F0-9]{64})\s' -or $Matches[1] -ne $ExpectedHash) {
        throw "Installed $PackageName differs from the APK selected for testing."
    }
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

$compilerJdk = Resolve-Java17Home -ExplicitPath $Jdk17Path -EnvironmentPath $env:JDK17_HOME -JavaHome $env:JAVA_HOME

Push-Location $repositoryRoot
try {
    $tasks = @(':app:assembleDebugAndroidTest')
    if (-not $ApkPath) { $tasks += ':app:assembleDebug' }
    $gradleArguments = @("-PrequireRime=true", "--no-parallel", "-Pandroid.injected.build.abi=$abi")
    if ($compilerJdk) { $gradleArguments += "-Porg.gradle.java.installations.paths=$($compilerJdk.Replace('\', '/'))" }
    & ./gradlew.bat @tasks @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Device test APK build failed." }
    $appPackage = 'dev.zeroinput.ime.debug'
    $testPackage = "$appPackage.test"
    $testDirectory = Join-Path $repositoryRoot "app/build/intermediates/apk/androidTest/debug"
    $testMetadata = Get-Content -LiteralPath (Join-Path $testDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
    if ($testMetadata.applicationId -ne $testPackage -or $testMetadata.variantName -ne 'debugAndroidTest' -or
        @($testMetadata.elements).Count -ne 1) { throw "Unexpected test APK identity or outputs." }
    $testOutput = [string]$testMetadata.elements[0].outputFile
    if ([IO.Path]::GetFileName($testOutput) -ne $testOutput -or [IO.Path]::GetExtension($testOutput) -ne '.apk') {
        throw 'Invalid test APK output name.'
    }
    $testApk = Join-Path $testDirectory $testOutput
    $selectedApk = if ($ApkPath) { (Resolve-Path -LiteralPath $ApkPath).Path }
        else { (Resolve-TestApkArtifact -RepositoryRoot $repositoryRoot -Abi $abi).Path }
    $expectedHash = (Get-FileHash -LiteralPath $selectedApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $expectedTestHash = (Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash.ToLowerInvariant()
    Invoke-Adb @("install", "-r", "-t", $selectedApk)
    Invoke-Adb @("install", "-r", "-t", $testApk)
    Assert-InstalledApk -PackageName $appPackage -ExpectedHash $expectedHash
    Assert-InstalledApk -PackageName $testPackage -ExpectedHash $expectedTestHash
    Write-Output "Installed APK SHA-256 verified: $expectedHash"
    Write-Output "Installed test APK SHA-256 verified: $expectedTestHash"

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
        $instrumentArguments = @("shell", "am", "instrument", "-w", "-r")
        if ($TestClass) { $instrumentArguments += @("-e", "class", $TestClass) }
        $instrumentArguments += "$testPackage/androidx.test.runner.AndroidJUnitRunner"
        $result = Invoke-Adb $instrumentArguments
        $instrumentationOutput = $result -join "`n"
        try { $counts = Read-InstrumentationResult $instrumentationOutput }
        catch { $result | Write-Output; throw }
        Write-Verbose $instrumentationOutput
        Write-Output "Verified $($counts.Passed) passed and $($counts.Skipped) skipped device tests."
        Assert-InstalledApk -PackageName $appPackage -ExpectedHash $expectedHash
        Assert-InstalledApk -PackageName $testPackage -ExpectedHash $expectedTestHash
    } finally {
        if ($changedMethod) { Invoke-Adb @("shell", "ime", "set", $originalMethod) }
    }

    $reportDirectory = Join-Path $repositoryRoot 'build/device-apk-verification'
    New-Item -Path $reportDirectory -ItemType Directory -Force | Out-Null
    $verifiedAt = [DateTime]::UtcNow
    $reportPath = Join-Path $reportDirectory "$($verifiedAt.ToString('yyyyMMdd-HHmmss'))-$([Guid]::NewGuid().ToString('N')).json"
    @{
        apk = $selectedApk
        sha256 = $expectedHash
        testApk = $testApk
        testApkSha256 = $expectedTestHash
        serial = $Serial
        tests = $TestClass
        passedTests = $counts.Passed
        skippedTests = $counts.Skipped
        passed = $true
        verifiedAtUtc = $verifiedAt.ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath "$reportPath.tmp" -Encoding utf8
    Move-Item -LiteralPath "$reportPath.tmp" -Destination $reportPath
    Write-Output "Device test report: $reportPath"
} finally {
    Pop-Location
}
