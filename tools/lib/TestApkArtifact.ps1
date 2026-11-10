Set-StrictMode -Version Latest

function Reset-TestApkPackaging {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

    # AGP incremental ZIP replacement can retain removed library/DEX bytes.
    # Reset only reproducible Debug packaging state, never native caches or deliveries.
    $root = [IO.Path]::GetFullPath($RepositoryRoot)
    $build = [IO.Path]::GetFullPath((Join-Path $root 'app/build'))
    $targets = @('intermediates/incremental/packageDebug', 'intermediates/apk/debug', 'outputs/apk/debug') |
        ForEach-Object { [IO.Path]::GetFullPath((Join-Path $build $_)) }
    foreach ($path in $targets) {
        if (-not $path.StartsWith($build + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Unsafe APK packaging reset path.'
        }
        $ancestor = $path
        while ($ancestor -and $ancestor.Length -ge $root.Length) {
            if (Test-Path -LiteralPath $ancestor) {
                if ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                    throw 'APK packaging reset cannot traverse links.'
                }
            }
            $ancestor = Split-Path $ancestor -Parent
        }
        if (Test-Path -LiteralPath $path) {
            if (@(Get-ChildItem -LiteralPath $path -Recurse -Force -Attributes ReparsePoint).Count -gt 0) {
                throw 'APK packaging reset cannot remove linked content.'
            }
        }
    }
    # Validate every path before removing any state.
    foreach ($path in $targets) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
    }
}

function Resolve-TestApkArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$Abi
    )

    # AGP changes output directories when android.injected.build.abi is set.
    # Follow the locator produced by this build, never prefer either directory.
    $buildRoot = [IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'app/build'))
    $locator = Join-Path $buildRoot 'intermediates/apk_ide_redirect_file/debug/createDebugApkListingFileRedirect/redirect.txt'
    if (-not (Test-Path -LiteralPath $locator -PathType Leaf)) { throw 'Current Gradle APK locator is missing.' }
    $listings = @(Get-Content -LiteralPath $locator | Where-Object { $_.StartsWith('listingFile=') })
    if ($listings.Count -ne 1) { throw 'Expected one Gradle APK listing locator.' }
    $relative = $listings[0].Substring('listingFile='.Length)
    if ([IO.Path]::IsPathRooted($relative)) { throw 'APK listing must use a relative path.' }
    $metadataPath = [IO.Path]::GetFullPath((Join-Path (Split-Path $locator -Parent) $relative))
    if (-not $metadataPath.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'APK listing escaped the Gradle build directory.'
    }
    $directory = Split-Path $metadataPath -Parent
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw 'Current Gradle APK metadata is missing.'
    }
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'dev.zeroinput.ime.debug' -or $metadata.variantName -ne 'debug') {
        throw 'Unexpected Gradle APK identity.'
    }
    $matches = @($metadata.elements | Where-Object {
        $filters = @($_.filters | Where-Object { $_.filterType -eq 'ABI' })
        if ($Abi -eq 'universal') { $filters.Count -eq 0 }
        else { $filters.Count -eq 1 -and $filters[0].value -eq $Abi }
    })
    if ($matches.Count -ne 1) { throw 'Expected exactly one current APK for the requested ABI.' }
    $name = [string]$matches[0].outputFile
    if ([IO.Path]::IsPathRooted($name)) { throw 'APK output must be relative to its metadata.' }
    $path = [IO.Path]::GetFullPath((Join-Path $directory $name))
    if (-not $path.StartsWith($directory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetExtension($path) -ne '.apk' -or -not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw 'Invalid or missing current APK output.'
    }
    return @{ Path = $path; MetadataPath = $metadataPath; LocatorPath = $locator; Element = $matches[0] }
}
