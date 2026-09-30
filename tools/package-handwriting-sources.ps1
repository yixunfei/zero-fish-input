[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RepositoryRoot,
    [Parameter(Mandatory = $true)][string]$Destination
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$sources = @(
    @{ Name = "tegaki-zinnia-simplified-chinese-light-0.3.zip"; Bytes = 7015643;
       Hash = "598787133a4d59fcf3a2fbc5654c68eaf35a8e422274efcea2035cc081f3446c" },
    @{ Name = "tegaki-zinnia-traditional-chinese-0.3.zip"; Bytes = 36773128;
       Hash = "f41032e67a4eff056813d243eabc32ea07eea404c714bdb5882a5b0fcda51690" }
)
$paths = [System.Collections.Generic.List[string]]::new()
foreach ($source in $sources) {
    $relative = "build/handwriting-stroke-model/$($source.Name)"
    $path = Join-Path $RepositoryRoot $relative
    if (-not (Test-Path -LiteralPath $path) -or
        (Get-Item -LiteralPath $path).Length -ne $source.Bytes -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $source.Hash) {
        throw "Missing or invalid corresponding source. Run python tools/prepare-handwriting-stroke-model.py."
    }
    $paths.Add($relative)
}
@("SOURCES.md", "NOTICE", "LICENSE", "LICENSES/Tegaki-LGPL-2.1.txt", "LICENSES/Zinnia-BSD-3-Clause.txt",
    "tools/prepare-handwriting-stroke-model.py", "tools/handwriting_quality.py", "model-scoring/build.gradle.kts") |
    ForEach-Object { $paths.Add($_) }
$modelDirectory = "model-scoring/src/main/kotlin/dev/zeroinput/model"
Get-ChildItem -LiteralPath (Join-Path $RepositoryRoot $modelDirectory) -File |
    Where-Object { $_.Name -like '*Handwriting*.kt' } |
    ForEach-Object { $paths.Add("$modelDirectory/$($_.Name)") }
foreach ($relative in $paths) {
    if (-not (Test-Path -LiteralPath (Join-Path $RepositoryRoot $relative) -PathType Leaf)) {
        throw "Missing source distribution file: $relative"
    }
}

# CreateNew prevents overwriting an earlier verified distribution artifact.
$stream = [IO.File]::Open($Destination, [IO.FileMode]::CreateNew)
$archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($relative in $paths) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,
            (Join-Path $RepositoryRoot $relative), $relative,
            [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose(); $stream.Dispose() }
Write-Output "Handwriting corresponding sources: $Destination"
