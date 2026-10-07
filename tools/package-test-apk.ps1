[CmdletBinding()]
param(
    [ValidateSet("universal", "arm64-v8a", "armeabi-v7a", "x86_64")]
    [string]$Abi = "universal",
    [switch]$Install,
    [string]$Serial,
    [switch]$SkipChecks,
    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
. (Join-Path $PSScriptRoot 'lib/TestApkArtifact.ps1')
$artifactRoot = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) }
    else { Join-Path $repositoryRoot "app\build\outputs\test-apk" }
$supportedAbis = @("arm64-v8a", "armeabi-v7a", "x86_64")

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$CaptureOutput
    )

    if ($CaptureOutput) {
        $output = @(& $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed ($LASTEXITCODE): $Executable $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
        }
        return $output
    }

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $Executable $($Arguments -join ' ')"
    }
}

function Resolve-AndroidSdkRoot {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:ANDROID_SDK_ROOT) { $candidates.Add($env:ANDROID_SDK_ROOT) }
    if ($env:ANDROID_HOME) { $candidates.Add($env:ANDROID_HOME) }

    $localProperties = Join-Path $repositoryRoot "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkLine) {
            $encodedPath = $sdkLine.Substring($sdkLine.IndexOf('=') + 1)
            $decodedPath = $encodedPath.Replace('\:', ':').Replace('\\', '\')
            $candidates.Add($decodedPath)
        }
    }

    if ($env:LOCALAPPDATA) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA "Android\Sdk"))
    }

    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
            return [System.IO.Path]::GetFullPath($candidate)
        }
    }
    throw "Android SDK was not found. Set ANDROID_SDK_ROOT or configure sdk.dir in local.properties."
}

function Resolve-AndroidTools {
    param([Parameter(Mandatory = $true)][string]$SdkRoot)

    $buildToolsRoot = Join-Path $SdkRoot "build-tools"
    $buildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
        Where-Object {
            (Test-Path -LiteralPath (Join-Path $_.FullName "aapt2.exe")) -and
            (Test-Path -LiteralPath (Join-Path $_.FullName "apksigner.bat"))
        } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if (-not $buildTools) { throw "aapt2 and apksigner were not found under $buildToolsRoot" }

    return @{
        Aapt2 = Join-Path $buildTools.FullName "aapt2.exe"
        ApkSigner = Join-Path $buildTools.FullName "apksigner.bat"
        Adb = Join-Path $SdkRoot "platform-tools\adb.exe"
    }
}

function Assert-TestApk {
    param(
        [Parameter(Mandatory = $true)][string]$ApkPath,
        [Parameter(Mandatory = $true)][hashtable]$AndroidTools,
        [Parameter(Mandatory = $true)][string[]]$ExpectedAbis
    )

    $badging = Invoke-CheckedCommand $AndroidTools.Aapt2 @("dump", "badging", $ApkPath) -CaptureOutput
    $badgingText = $badging -join "`n"
    $packageMatch = [regex]::Match($badgingText, "package: name='([^']+)'[^\r\n]*versionName='([^']+)'")
    if (-not $packageMatch.Success) { throw "Unable to read APK package metadata: $ApkPath" }
    $packageName = $packageMatch.Groups[1].Value
    if ($packageName -ne "dev.zeroinput.ime.debug") {
        throw "Unexpected test package name: $packageName"
    }

    $nativeLine = @($badging | Where-Object { $_ -match '^native-code:' }) | Select-Object -First 1
    $actualAbis = @([regex]::Matches($nativeLine, "'([^']+)'") | ForEach-Object { $_.Groups[1].Value })
    if (@(Compare-Object ($ExpectedAbis | Sort-Object) ($actualAbis | Sort-Object)).Count -ne 0) {
        throw "Unexpected APK ABIs. Expected $($ExpectedAbis -join ', '); found $($actualAbis -join ', ')."
    }

    $permissions = Invoke-CheckedCommand $AndroidTools.Aapt2 @("dump", "permissions", $ApkPath) -CaptureOutput
    $permissionNames = @($permissions | ForEach-Object {
        $match = [regex]::Match($_, "uses-permission(?:-sdk-\d+)?: name='([^']+)'")
        if ($match.Success) { $match.Groups[1].Value }
    })
    $allowedPermissions = @(
        "android.permission.USE_BIOMETRIC",
        "android.permission.USE_FINGERPRINT",
        # Only the user-enabled, content-free system clipboard reminder uses notifications.
        "android.permission.POST_NOTIFICATIONS",
        # The separately enabled clipboard overlay requires explicit user consent.
        "android.permission.SYSTEM_ALERT_WINDOW",
        # User-approved optional AI transport; input and clipboard features remain offline.
        "android.permission.INTERNET",
        "$packageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    )
    $unexpectedPermissions = @($permissionNames | Where-Object { $_ -notin $allowedPermissions })
    if ($unexpectedPermissions.Count -gt 0) {
        throw "Unexpected packaged permissions: $($unexpectedPermissions -join ', ')"
    }

    Invoke-CheckedCommand $AndroidTools.ApkSigner @("verify", $ApkPath) -CaptureOutput | Out-Null
    return @{ PackageName = $packageName; VersionName = $packageMatch.Groups[2].Value }
}

function Install-TestApk {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$ApkPath,
        [Parameter(Mandatory = $true)][string[]]$ApkAbis,
        [string]$RequestedSerial
    )

    if (-not (Test-Path -LiteralPath $Adb)) { throw "ADB was not found: $Adb" }
    $deviceLines = Invoke-CheckedCommand $Adb @("devices", "-l") -CaptureOutput
    $devices = @($deviceLines | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device(?:\s|$)') { $Matches[1] }
    })
    $target = if ($RequestedSerial) {
        if ($RequestedSerial -notin $devices) { throw "Requested ADB device is not connected and authorized: $RequestedSerial" }
        $RequestedSerial
    } else {
        if ($devices.Count -ne 1) { throw "Connect exactly one authorized device or pass -Serial. Found $($devices.Count)." }
        $devices[0]
    }

    $deviceAbiText = (Invoke-CheckedCommand $Adb @("-s", $target, "shell", "getprop", "ro.product.cpu.abilist") -CaptureOutput) -join ""
    if ([string]::IsNullOrWhiteSpace($deviceAbiText)) {
        $deviceAbiText = (Invoke-CheckedCommand $Adb @("-s", $target, "shell", "getprop", "ro.product.cpu.abi") -CaptureOutput) -join ""
    }
    $deviceAbis = @($deviceAbiText.Trim().Split(',') | Where-Object { $_ })
    $compatibleAbis = @($deviceAbis | Where-Object { $_ -in $ApkAbis })
    if ($compatibleAbis.Count -eq 0) {
        throw "APK ABI is incompatible with device $target ($($deviceAbis -join ', '))."
    }

    Invoke-CheckedCommand $Adb @("-s", $target, "install", "-r", $ApkPath)
    Write-Output "Installed on $target. Enable zero fish input from Android input method settings."
}

if ($Serial -and -not $Install) { throw "-Serial requires -Install." }
if (-not (Test-Path -LiteralPath $gradleWrapper)) { throw "Gradle wrapper was not found: $gradleWrapper" }

$sdkRoot = Resolve-AndroidSdkRoot
$androidTools = Resolve-AndroidTools $sdkRoot
$expectedAbis = if ($Abi -eq "universal") { $supportedAbis } else { @($Abi) }

Push-Location $repositoryRoot
try {
    if (-not $SkipChecks) {
        Write-Output "Running unit tests, privacy checks, and Android Lint..."
        Invoke-CheckedCommand $gradleWrapper @(
            "test", "privacyCheck", ":app:lintDebug", "--no-parallel", "-PrequireRime=true"
        )
    }

    Write-Output "Building the $Abi debug test APK..."
    $buildArguments = @(
        ":app:assembleDebug", "--no-parallel", "-PrequireRime=true"
    )
    if ($Abi -eq "universal") {
        $buildArguments += "-PtestUniversalApk=true"
    } else {
        $buildArguments += "-Pandroid.injected.build.abi=$Abi"
    }
    Invoke-CheckedCommand $gradleWrapper $buildArguments
} finally {
    Pop-Location
}

$artifact = Resolve-TestApkArtifact -RepositoryRoot $repositoryRoot -Abi $Abi
$sourceApk = $artifact.Path

$metadata = Assert-TestApk $sourceApk $androidTools $expectedAbis
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$artifactDirectory = Join-Path $artifactRoot $timestamp
New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
$artifactName = "zero-fish-input-$($metadata.VersionName)-$Abi.apk"
$artifactApk = Join-Path $artifactDirectory $artifactName
Copy-Item -LiteralPath $sourceApk -Destination $artifactApk
$sourceHash = (Get-FileHash -LiteralPath $sourceApk -Algorithm SHA256).Hash
if ((Get-FileHash -LiteralPath $artifactApk -Algorithm SHA256).Hash -ne $sourceHash) {
    throw 'Packaged APK differs from the current Gradle output.'
}
@{
    sourceApk = $sourceApk
    sourceMetadata = $artifact.MetadataPath
    sourceLocator = $artifact.LocatorPath
    applicationId = $metadata.PackageName
    versionName = $metadata.VersionName
    abi = $Abi
    sha256 = $sourceHash.ToLowerInvariant()
    packagedAtUtc = [DateTime]::UtcNow.ToString('o')
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $artifactDirectory 'BUILD-PROVENANCE.json') -Encoding utf8

$noticesName = "zero-fish-input-$($metadata.VersionName)-notices.zip"
$noticesArchive = Join-Path $artifactDirectory $noticesName
$noticePaths = @("LICENSE", "NOTICE", "THIRD_PARTY.md", "SOURCES.md", "LICENSES") |
    ForEach-Object { Join-Path $repositoryRoot $_ }
Compress-Archive -LiteralPath $noticePaths -DestinationPath $noticesArchive

$sourcesName = "zero-fish-input-$($metadata.VersionName)-handwriting-sources.zip"
$sourcesArchive = Join-Path $artifactDirectory $sourcesName
& (Join-Path $PSScriptRoot "package-handwriting-sources.ps1") -RepositoryRoot $repositoryRoot -Destination $sourcesArchive

$hash = (Get-FileHash -LiteralPath $artifactApk -Algorithm SHA256).Hash.ToLowerInvariant()
$noticesHash = (Get-FileHash -LiteralPath $noticesArchive -Algorithm SHA256).Hash.ToLowerInvariant()
$sourcesHash = (Get-FileHash -LiteralPath $sourcesArchive -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumPath = Join-Path $artifactDirectory "SHA256SUMS.txt"
[System.IO.File]::WriteAllText(
    $checksumPath,
    "$hash  $artifactName$([Environment]::NewLine)$noticesHash  $noticesName$([Environment]::NewLine)$sourcesHash  $sourcesName$([Environment]::NewLine)",
    [System.Text.UTF8Encoding]::new($false)
)

if ($Install) {
    Install-TestApk $androidTools.Adb $artifactApk $expectedAbis $Serial
}

$gradleCheckStatus = if ($SkipChecks) { "skipped by request" } else { "passed" }
Write-Output "Test APK: $artifactApk"
Write-Output "License notices: $noticesArchive"
Write-Output "Corresponding sources: $sourcesArchive"
Write-Output "SHA-256: $hash"
Write-Output "Gradle checks: $gradleCheckStatus"
Write-Output "APK signature, ABI, and permission validation: passed"
