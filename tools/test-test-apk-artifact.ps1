[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/TestApkArtifact.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('zeroinput-apk-' + [Guid]::NewGuid().ToString('N'))
$currentDirectory = Join-Path $fixture 'app/build/intermediates/apk/debug'
$oldDirectory = Join-Path $fixture 'app/build/outputs/apk/debug'
$locatorDirectory = Join-Path $fixture 'app/build/intermediates/apk_ide_redirect_file/debug/createDebugApkListingFileRedirect'
New-Item -ItemType Directory -Path $currentDirectory, $oldDirectory, $locatorDirectory -Force | Out-Null
$locator = Join-Path $locatorDirectory 'redirect.txt'

function Write-FixtureMetadata([string]$Directory, [object[]]$Elements) {
    @{ applicationId = 'dev.zeroinput.ime.debug'; variantName = 'debug'; elements = $Elements } |
        ConvertTo-Json -Depth 8 | Set-Content (Join-Path $Directory 'output-metadata.json')
}
function Assert-Rejected([scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Expected artifact resolution to fail closed.' }
}

try {
    $element = @{ filters = @(@{ filterType = 'ABI'; value = 'arm64-v8a' }); outputFile = 'app.apk' }
    Write-FixtureMetadata $oldDirectory @($element)
    Set-Content (Join-Path $oldDirectory 'app.apk') 'stale'
    Assert-Rejected { Resolve-TestApkArtifact $fixture 'arm64-v8a' }
    Set-Content $locator 'listingFile=../../../apk/debug/output-metadata.json'
    Assert-Rejected { Resolve-TestApkArtifact $fixture 'arm64-v8a' }
    Write-FixtureMetadata $currentDirectory @($element)
    Set-Content (Join-Path $currentDirectory 'app.apk') 'current'
    $result = Resolve-TestApkArtifact $fixture 'arm64-v8a'
    if ((Get-Content $result.Path -Raw).Trim() -ne 'current') { throw 'Selected stale APK.' }
    Assert-Rejected { Resolve-TestApkArtifact $fixture 'x86_64' }
    Write-FixtureMetadata $currentDirectory @($element, $element)
    Assert-Rejected { Resolve-TestApkArtifact $fixture 'arm64-v8a' }
    $element.outputFile = '../../../../../outside.apk'
    Write-FixtureMetadata $currentDirectory @($element)
    Assert-Rejected { Resolve-TestApkArtifact $fixture 'arm64-v8a' }
    Write-FixtureMetadata $currentDirectory @(@{ filters = @(); outputFile = 'app.apk' })
    $result = Resolve-TestApkArtifact $fixture 'universal'
    if ((Get-Content $result.Path -Raw).Trim() -ne 'current') { throw 'Universal selection failed.' }
    Write-FixtureMetadata $oldDirectory @(@{ filters = @(); outputFile = 'app.apk' })
    Set-Content $locator 'listingFile=../../../../outputs/apk/debug/output-metadata.json'
    $result = Resolve-TestApkArtifact $fixture 'universal'
    if ($result.Path -ne (Join-Path $oldDirectory 'app.apk')) { throw 'Current locator was not followed after output relocation.' }
    Set-Content $locator 'listingFile=../../../../../../outside.json'
    Assert-Rejected { Resolve-TestApkArtifact $fixture 'universal' }
    $nativeSentinel = Join-Path $fixture 'engine-rime/build/native-sentinel'
    $deliverySentinel = Join-Path $fixture 'app/build/outputs/test-apk/sentinel'
    foreach ($sentinel in @($nativeSentinel, $deliverySentinel)) {
        New-Item -ItemType Directory -Path (Split-Path $sentinel -Parent) -Force | Out-Null
        Set-Content -LiteralPath $sentinel 'preserve'
    }
    New-Item -ItemType Directory -Path (Join-Path $fixture 'app/build/intermediates/incremental/packageDebug') -Force | Out-Null
    Reset-TestApkPackaging -RepositoryRoot $fixture
    if ((Test-Path -LiteralPath $currentDirectory) -or (Test-Path -LiteralPath $oldDirectory)) {
        throw 'Debug packaging output was not reset.'
    }
    foreach ($sentinel in @($nativeSentinel, $deliverySentinel)) {
        if ((Get-Content -LiteralPath $sentinel -Raw).Trim() -ne 'preserve') { throw 'Unrelated build data changed.' }
    }
    $linkedOutput = Join-Path $fixture 'app/build/outputs/apk/debug'
    New-Item -ItemType Directory -Path (Split-Path $linkedOutput -Parent) -Force | Out-Null
    $linkTarget = Join-Path $fixture 'protected-link-target'
    New-Item -ItemType Directory -Path $linkTarget -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $linkTarget 'sentinel') 'preserve'
    New-Item -ItemType Junction -Path $linkedOutput -Target $linkTarget | Out-Null
    Assert-Rejected { Reset-TestApkPackaging -RepositoryRoot $fixture }
    if ((Get-Content -LiteralPath (Join-Path $linkTarget 'sentinel') -Raw).Trim() -ne 'preserve') {
        throw 'Linked output target was modified.'
    }
    Write-Output 'PASS: missing locator/listing rejected; stale output ignored; locator relocation followed; missing ABI, duplicate entries and path escapes rejected; universal selected.'
    Write-Output 'PASS: packaging reset removes only Debug ZIP state, rejects links and preserves native caches and deliveries.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notlike 'zeroinput-apk-*') { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
