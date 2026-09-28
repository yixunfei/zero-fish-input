param()

$ErrorActionPreference = "Stop"

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$thirdPartyRoot = Join-Path $repositoryRoot "third_party"
$librimeRoot = Join-Path $thirdPartyRoot "librime"
$rimeDependenciesRoot = Join-Path $thirdPartyRoot "rime-deps"
$boostRoot = Join-Path $thirdPartyRoot "boost"
$assetRoot = Join-Path $repositoryRoot "engine-rime\src\main\assets\rime"
$expectedRimeCommit = "1c23358157934bd6e6d6981f0c0164f05393b497"
$lunaCommit = "46acf03142c12b5aeed7002675046bf7255eed35"
$essayCommit = "0766c929ec3e578c2c80861e988decd3703a1c3d"

New-Item -ItemType Directory -Force -Path $thirdPartyRoot, $assetRoot | Out-Null

if (-not (Test-Path (Join-Path $librimeRoot "CMakeLists.txt"))) {
    git clone --branch 1.13.1 --depth 1 `
        https://github.com/rime/librime.git $librimeRoot
    if ($LASTEXITCODE -ne 0) { throw "Unable to clone librime" }
}

$actualRimeCommit = (git -C $librimeRoot rev-parse HEAD).Trim()
if ($actualRimeCommit -ne $expectedRimeCommit) {
    throw "Unexpected librime commit: $actualRimeCommit"
}

function Install-PinnedArchive {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Sha256
    )

    $destination = Join-Path $rimeDependenciesRoot $Name
    if (Test-Path (Join-Path $destination "CMakeLists.txt")) { return }
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    $archive = Join-Path $thirdPartyRoot "$Name.tar.gz"
    if (-not (Test-Path $archive)) { Invoke-WebRequest $Url -OutFile $archive }
    $actualHash = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $Sha256) { throw "$Name archive checksum mismatch" }
    tar --strip-components=1 -xf $archive -C $destination
    if ($LASTEXITCODE -ne 0) { throw "Unable to extract $Name" }
}

Install-PinnedArchive `
    -Name "leveldb" `
    -Url "https://codeload.github.com/google/leveldb/tar.gz/99b3c03b3284f5886f9ef9a4ef703d57373e61be" `
    -Sha256 "bc87b9bbc5674c91246a89813355e78401759761342cc049e1c3d56350a8a9d1"
Install-PinnedArchive `
    -Name "marisa-trie" `
    -Url "https://codeload.github.com/rime/marisa-trie/tar.gz/0d4e8ab58eec355facf8f65ff11ef811b330e373" `
    -Sha256 "ab87bd9bff51382cf67e00605330945dec158911dde4504f93fde0dc8acfaaf2"
Install-PinnedArchive `
    -Name "opencc" `
    -Url "https://codeload.github.com/BYVoid/OpenCC/tar.gz/e5d6c5f1b78e28a5797e7ad3ede3513314e544b7" `
    -Sha256 "08b54987a651d14dc7afb688381a5007680e925a224e10426f3800d35ef68835"
Install-PinnedArchive `
    -Name "yaml-cpp" `
    -Url "https://codeload.github.com/jbeder/yaml-cpp/tar.gz/f7320141120f720aecc4c32be25586e7da9eb978" `
    -Sha256 "2fd3bf695ccc056835a70dd6d3046312cc1004e8338b8b5c91646918a27b7ef6"

if (-not (Test-Path (Join-Path $boostRoot "CMakeLists.txt"))) {
    $boostArchive = Join-Path $thirdPartyRoot "boost-1.89.0-cmake.tar.xz"
    if (-not (Test-Path $boostArchive)) {
        Invoke-WebRequest `
            "https://github.com/boostorg/boost/releases/download/boost-1.89.0/boost-1.89.0-cmake.tar.xz" `
            -OutFile $boostArchive
    }
    $boostHash = (Get-FileHash $boostArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($boostHash -ne "67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74") {
        throw "Boost archive checksum mismatch"
    }
    tar -xf $boostArchive -C $thirdPartyRoot
    if ($LASTEXITCODE -ne 0) { throw "Unable to extract Boost" }
    Move-Item -LiteralPath (Join-Path $thirdPartyRoot "boost-1.89.0") -Destination $boostRoot
}

function Receive-VerifiedFile {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][string]$Sha256
    )

    if (-not (Test-Path $Destination)) {
        Invoke-WebRequest $Url -OutFile $Destination
    }
    $actualHash = (Get-FileHash $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $Sha256) { throw "Checksum mismatch: $Destination" }
}

Receive-VerifiedFile `
    -Url "https://raw.githubusercontent.com/rime/rime-luna-pinyin/$lunaCommit/luna_pinyin.dict.yaml" `
    -Destination (Join-Path $assetRoot "luna_pinyin.dict.yaml") `
    -Sha256 "270b0c6879436d7b0b606aa684507ea7e173c62b6d5df3966804637872d520fd"

Receive-VerifiedFile `
    -Url "https://raw.githubusercontent.com/rime/rime-essay/$essayCommit/essay.txt" `
    -Destination (Join-Path $assetRoot "essay.txt") `
    -Sha256 "196d508a9bb12fc6d711eed83f5ddc4d5c2d382d92d15b4e51175e4d3802cfab"

Write-Output "librime $actualRimeCommit and Rime data are ready."
