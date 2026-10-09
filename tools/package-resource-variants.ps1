[CmdletBinding()]
param([ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86_64')][string]$Abi = 'arm64-v8a')
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'lib/TestApkArtifact.ps1')
Push-Location $repositoryRoot
try {
    foreach ($bundle in @('full', 'lite')) {
        # APK incremental updates retain removed ZIP bytes. Recreate the archive
        # when switching resource contents so lite delivery reclaims those bytes.
        $packagingState = Join-Path $repositoryRoot 'app/build/intermediates/incremental/packageDebug'
        if (Test-Path -LiteralPath $packagingState) { Remove-Item -LiteralPath $packagingState -Recurse -Force }
        foreach ($relative in @('app/build/intermediates/apk/debug', 'app/build/outputs/apk/debug')) {
            $apkDirectory = Join-Path $repositoryRoot $relative
            if (Test-Path -LiteralPath $apkDirectory) {
                Get-ChildItem -LiteralPath $apkDirectory -Filter '*.apk' -File |
                    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
            }
        }
        & ./gradlew.bat :app:assembleDebug -PrequireRime=true "-PresourceBundle=$bundle" "-Pandroid.injected.build.abi=$Abi" "-Pandroid.injected.testOnly=false" --no-parallel
        if ($LASTEXITCODE -ne 0) { throw 'Variant build failed' }
        $artifact = Resolve-TestApkArtifact -RepositoryRoot $repositoryRoot -Abi $Abi
        & python tools/resources/verify-apk.py $bundle $artifact.Path
        if ($LASTEXITCODE -ne 0) { throw 'Variant contents failed validation' }
        $destination = Join-Path $repositoryRoot "build/delivery/$bundle"
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        $path = Join-Path $destination "zeroinput-$bundle-$Abi-debug.apk"
        Copy-Item -LiteralPath $artifact.Path -Destination $path
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        [IO.File]::WriteAllText("$path.sha256", "$hash  $([IO.Path]::GetFileName($path))`n")
    }
} finally { Pop-Location }
