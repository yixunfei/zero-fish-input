$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "pinned-source-archive.ps1")

$temporaryBase = [IO.Path]::GetTempPath()
$testRoot = [IO.Path]::GetFullPath((Join-Path $temporaryBase ("zeroinput-source-test-" + [IO.Path]::GetRandomFileName())))
if (-not $testRoot.StartsWith([IO.Path]::GetFullPath($temporaryBase), [StringComparison]::OrdinalIgnoreCase)) {
    throw "Test directory escaped its temporary root"
}
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    $source = Join-Path $testRoot "source"
    New-Item -ItemType Directory -Path $source | Out-Null
    Set-Content -LiteralPath (Join-Path $source "CMakeLists.txt") -Value "project(fixture)"
    Set-Content -LiteralPath (Join-Path $source "source.txt") -Value "reviewed"
    $archive = Join-Path $testRoot "fixture.tar.gz"
    tar -czf $archive -C $testRoot source
    if ($LASTEXITCODE -ne 0) { throw "Unable to create test fixture" }
    $checksum = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    $destination = Join-Path $testRoot "installed"
    $arguments = @{ Archive = $archive; Destination = $destination; Sha256 = $checksum
        TemporaryRoot = (Join-Path $testRoot "verification") }
    Install-VerifiedSourceArchive @arguments
    Install-VerifiedSourceArchive @arguments

    $arguments.Sha256 = "0" * 64
    $rejected = $false
    try { Install-VerifiedSourceArchive @arguments } catch { $rejected = $true }
    if (-not $rejected) { throw "Existing sources bypassed archive verification" }
    $arguments.Sha256 = $checksum

    Set-Content -LiteralPath (Join-Path $destination "source.txt") -Value "local modification"
    $rejected = $false
    try { Install-VerifiedSourceArchive @arguments } catch { $rejected = $true }
    if (-not $rejected -or (Get-Content -LiteralPath (Join-Path $destination "source.txt")) -ne "local modification") {
        throw "Modified sources were accepted or overwritten"
    }
    Copy-Item -LiteralPath (Join-Path $source "source.txt") -Destination (Join-Path $destination "source.txt")
    Set-Content -LiteralPath (Join-Path $destination "unexpected.cmake") -Value "unreviewed"
    $rejected = $false
    try { Install-VerifiedSourceArchive @arguments } catch { $rejected = $true }
    if (-not $rejected -or -not (Test-Path -LiteralPath (Join-Path $destination "unexpected.cmake"))) {
        throw "Unexpected files were accepted or removed"
    }
    Write-Output "Pinned archive installation and four integrity checks passed."
} finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
