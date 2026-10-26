function Resolve-Java17Home {
    [CmdletBinding()]
    param([string]$ExplicitPath, [string]$EnvironmentPath, [string]$JavaHome)

    $candidates = if ($ExplicitPath) { @($ExplicitPath) } else {
        $EnvironmentPath
        if ($JavaHome) { Join-Path (Split-Path $JavaHome -Parent) 'jdk17' }
    }
    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        $jdkHome = [IO.Path]::GetFullPath($candidate)
        $release = Join-Path $jdkHome 'release'
        if ((Test-Path -LiteralPath (Join-Path $jdkHome 'bin/javac.exe')) -and
            (Test-Path -LiteralPath $release) -and
            @(Get-Content -LiteralPath $release | Where-Object { $_ -match '^JAVA_VERSION="17(?:\.|\+|")' }).Count -eq 1) {
            return $jdkHome
        }
    }
    if ($ExplicitPath) { throw 'Jdk17Path must contain a JDK 17 release and javac.' }
    return $null
}
