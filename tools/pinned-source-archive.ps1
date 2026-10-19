function Install-VerifiedSourceArchive {
    param(
        [Parameter(Mandatory = $true)][string]$Archive,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][string]$Sha256,
        [Parameter(Mandatory = $true)][string]$TemporaryRoot
    )

    $actualHash = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $Sha256) { throw "Source archive checksum mismatch" }
    $temporaryBase = [System.IO.Path]::GetFullPath($TemporaryRoot)
    New-Item -ItemType Directory -Force -Path $temporaryBase | Out-Null
    $staging = [System.IO.Path]::GetFullPath((Join-Path $temporaryBase ([System.IO.Path]::GetRandomFileName())))
    $prefix = $temporaryBase.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $staging.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Source verification path escaped its temporary root"
    }
    New-Item -ItemType Directory -Path $staging | Out-Null
    try {
        tar --strip-components=1 -xf $Archive -C $staging
        if ($LASTEXITCODE -ne 0) { throw "Unable to extract pinned source archive" }
        if (-not (Test-Path -LiteralPath (Join-Path $staging "CMakeLists.txt") -PathType Leaf)) {
            throw "Pinned source archive has no build entry point"
        }
        if (Test-Path -LiteralPath $Destination) {
            Assert-SourceTreeMatches -Reference $staging -Existing $Destination
        } else {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
            Move-Item -LiteralPath $staging -Destination $Destination
        }
    } finally {
        # Only this newly created, checked temporary directory may be removed.
        if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
    }
}

function Assert-SourceTreeMatches {
    param([string]$Reference, [string]$Existing)

    foreach ($tree in @($Reference, $Existing)) {
        $root = Get-Item -LiteralPath $tree -Force
        if (-not $root.PSIsContainer -or ($root.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Existing source tree is not a regular directory"
        }
        $links = Get-ChildItem -LiteralPath $tree -Force -Recurse |
            Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }
        if ($links) { throw "Source tree contains unsupported links" }
    }
    $expected = @(Get-ChildItem -LiteralPath $Reference -Force -Recurse -File)
    $actual = @(Get-ChildItem -LiteralPath $Existing -Force -Recurse -File)
    if ($expected.Count -ne $actual.Count) {
        throw "Existing source tree differs from the pinned archive; preserve changes and use a fresh directory"
    }
    foreach ($file in $expected) {
        $relative = $file.FullName.Substring($Reference.TrimEnd([IO.Path]::DirectorySeparatorChar).Length + 1)
        $target = Join-Path $Existing $relative
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "Pinned source file is missing" }
        $existingFile = Get-Item -LiteralPath $target
        if ($existingFile.Length -ne $file.Length -or
            (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne
            (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash) {
            throw "Existing source file differs from the pinned archive; local files were preserved"
        }
    }
}
