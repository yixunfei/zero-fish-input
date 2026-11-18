[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SdkRoot,
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$NdkVersion = '28.2.13676358'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$compiler = Join-Path $SdkRoot "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "JNI failure check command failed: $Executable" }
}

$abi = (& $adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
if ($LASTEXITCODE -ne 0 -or $abi -ne 'x86_64') { throw 'Select an isolated x86_64 Android test device.' }
$output = Join-Path $root '.tmp-evidence/jni_failure_test'
New-Item -ItemType Directory -Force -Path (Split-Path $output -Parent) | Out-Null
Invoke-Checked $compiler @('--target=x86_64-linux-android26', '-std=c++17', '-static-libstdc++',
    "-I$(Join-Path $root 'engine-rime/src/main/cpp')",
    (Join-Path $root 'engine-rime/src/test/cpp/jni_failure_test.cpp'), '-o', $output)
$remote = '/data/local/tmp/zero_jni_failure_test'
try {
    Invoke-Checked $adb @('-s', $Serial, 'push', $output, $remote)
    Invoke-Checked $adb @('-s', $Serial, 'shell', 'chmod', '700', $remote)
    Invoke-Checked $adb @('-s', $Serial, 'shell', $remote)
} finally {
    & $adb -s $Serial shell rm -f $remote | Out-Null
}
