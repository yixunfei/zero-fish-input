[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/JavaToolchain.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('zeroinput-jdk-' + [Guid]::NewGuid().ToString('N'))

function New-JdkFixture([string]$Name, [string]$Version) {
    $directory = Join-Path $fixture $Name
    New-Item -ItemType Directory -Path (Join-Path $directory 'bin') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $directory 'bin/javac.exe') ''
    Set-Content -LiteralPath (Join-Path $directory 'release') ('JAVA_VERSION="' + $Version + '"')
    return $directory
}
function Assert-Home([string]$Expected, [object]$Actual) {
    if ($Actual -is [array] -or $Actual -ne $Expected) { throw 'Unexpected compiler toolchain selection.' }
}

try {
    $jdk17 = New-JdkFixture 'jdk17' '17.0.16'
    $jdk21 = New-JdkFixture 'jdk21' '21.0.8'
    $custom = New-JdkFixture 'custom compiler' '17'
    Assert-Home $jdk17 (Resolve-Java17Home -EnvironmentPath $jdk17 -JavaHome $jdk21)
    Assert-Home $jdk17 (Resolve-Java17Home -JavaHome $jdk21)
    Assert-Home $custom (Resolve-Java17Home -ExplicitPath $custom -EnvironmentPath $jdk17 -JavaHome $jdk21)
    Assert-Home $custom (Resolve-Java17Home -EnvironmentPath $custom)
    Assert-Home $jdk17 (Resolve-Java17Home -EnvironmentPath $jdk21 -JavaHome $jdk21)
    if ($null -ne (Resolve-Java17Home)) { throw 'Missing hints must leave Gradle detection available.' }
    if ($null -ne (Resolve-Java17Home -EnvironmentPath $jdk21)) { throw 'A non-17 JDK must not be selected.' }
    $rejected = $false
    try { Resolve-Java17Home -ExplicitPath $jdk21 -EnvironmentPath $jdk17 | Out-Null }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'An invalid explicit override must fail without silently falling back.' }
    Write-Output 'PASS: explicit, environment and sibling discovery; single result; missing hints and invalid overrides.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notlike 'zeroinput-jdk-*') { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
