[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [string]$ExpectedPackage,
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64")]
    [string]$ExpectedAbi,
    [int]$MinimumSdk = 26
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$resolvedApk = [IO.Path]::GetFullPath($ApkPath)
if (-not (Test-Path -LiteralPath $resolvedApk -PathType Leaf)) {
    throw "APK was not found: $resolvedApk"
}

function Resolve-AndroidTool {
    param([Parameter(Mandatory = $true)][string]$Name)

    $sdkCandidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)
    $localProperties = Join-Path (Get-Location) "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) {
            $sdkCandidates += $sdkLine.Substring($sdkLine.IndexOf('=') + 1).Replace('\\:', ':').Replace('\\\\', '\\')
        }
    }
    if ($env:LOCALAPPDATA) { $sdkCandidates += Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $sdkCandidates = $sdkCandidates |
        Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    foreach ($sdk in $sdkCandidates) {
        $tool = Get-ChildItem (Join-Path $sdk "build-tools") -Directory |
            Sort-Object { [version]$_.Name } -Descending |
            ForEach-Object { Join-Path $_.FullName $Name } |
            Where-Object { Test-Path -LiteralPath $_ } |
            Select-Object -First 1
        if ($tool) { return $tool }
    }
    throw "Android Build Tools executable was not found: $Name"
}

$aapt2 = Resolve-AndroidTool "aapt2.exe"
$apksigner = Resolve-AndroidTool "apksigner.bat"
$badging = @(& $aapt2 dump badging $resolvedApk 2>&1 | ForEach-Object { $_.ToString() })
if ($LASTEXITCODE -ne 0) { throw "aapt2 could not parse the APK: $resolvedApk" }
$badgingText = $badging -join "`n"

$manifestTree = @(& $aapt2 dump xmltree $resolvedApk --file AndroidManifest.xml 2>&1 |
    ForEach-Object { $_.ToString() })
if ($LASTEXITCODE -ne 0) { throw "aapt2 could not read AndroidManifest.xml: $resolvedApk" }
if (@($manifestTree | Where-Object { $_ -match 'testOnly.*=true' }).Count -gt 0) {
    throw "APK is marked testOnly and cannot be installed from a file manager: $resolvedApk"
}

$package = [regex]::Match($badgingText, "package: name='([^']+)'[^\r\n]*versionCode='([^']+)'[^\r\n]*versionName='([^']+)'")
if (-not $package.Success) { throw "APK package metadata is missing: $resolvedApk" }
if ($ExpectedPackage -and $package.Groups[1].Value -ne $ExpectedPackage) {
    throw "Unexpected package $($package.Groups[1].Value); expected $ExpectedPackage."
}
$sdk = [regex]::Match($badgingText, "minSdkVersion:'(\d+)'")
if (-not $sdk.Success -or [int]$sdk.Groups[1].Value -lt $MinimumSdk) {
    throw "APK minSdkVersion is below the required $MinimumSdk."
}
$nativeMatch = [regex]::Match($badgingText, "native-code:([^\r\n]+)")
[string[]]$native = if ($nativeMatch.Success) {
    @($nativeMatch.Groups[1].Value -split "'" | Where-Object { $_ -match "^[a-z0-9_-]+$" })
} else { @() }
if ($ExpectedAbi -and $native -notcontains $ExpectedAbi) {
    throw "APK does not contain the expected ABI $ExpectedAbi. Found: $($native -join ', ')."
}
if ($native.Count -eq 0) { throw "APK has no native-code entry." }

$verification = @(& $apksigner verify --verbose $resolvedApk 2>&1 | ForEach-Object { $_.ToString() })
if ($LASTEXITCODE -ne 0 -or ($verification -match "DOES NOT VERIFY|Missing META-INF|No signers found")) {
    throw "APK is not signed or has an invalid signature: $resolvedApk"
}

Write-Output "Installable APK verified: $resolvedApk"
Write-Output "Package: $($package.Groups[1].Value) $($package.Groups[3].Value)"
Write-Output "ABIs: $($native -join ', ')"
