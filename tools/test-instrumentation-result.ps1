[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/InstrumentationResult.ps1')

foreach ($output in @("OK (1 test)`n", "Time: 1.2`r`n`r`nOK (42 tests)`r`n`r`nINSTRUMENTATION_CODE: -1")) {
    Assert-InstrumentationSuccess $output
}
foreach ($output in @('', 'OK (0 tests)', 'OK (00 tests)', 'prefix OK (1 test)',
    "OK (1 test)`nOK (2 tests)", "OK (1 test)`nFAILURES!!!",
    "OK (1 test)`nINSTRUMENTATION_FAILED: runner", "OK (1 test)`nINSTRUMENTATION_ABORTED: runner",
    "OK (1 test)`nINSTRUMENTATION_RESULT: shortMsg=Process crashed.", 'INSTRUMENTATION_CODE: -1')) {
    $rejected = $false
    try { Assert-InstrumentationSuccess $output } catch { $rejected = $true }
    if (-not $rejected) { throw 'Invalid instrumentation output was accepted.' }
}
Write-Output 'Instrumentation result checks passed: 2 valid and 10 invalid outputs.'
