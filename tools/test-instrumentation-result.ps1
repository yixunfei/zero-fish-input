[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/InstrumentationResult.ps1')

$valid = @(
    @{ Output = "OK (1 test)`n"; Count = 1 }
    @{ Output = "Time: 1.2`r`n`r`nOK (42 tests)`r`n`r`nINSTRUMENTATION_CODE: -1"; Count = 42 }
)
foreach ($fixture in $valid) {
    if ((Get-PassedInstrumentationTestCount $fixture.Output) -ne $fixture.Count) {
        throw 'Unexpected instrumentation test count.'
    }
}
foreach ($output in @('', 'OK (0 tests)', 'OK (00 tests)', 'prefix OK (1 test)',
    "OK (1 test)`nOK (2 tests)", "OK (1 test)`nFAILURES!!!",
    "OK (1 test)`nINSTRUMENTATION_FAILED: runner", "OK (1 test)`nINSTRUMENTATION_ABORTED: runner",
    "OK (1 test)`nINSTRUMENTATION_RESULT: shortMsg=Process crashed.", 'INSTRUMENTATION_CODE: -1')) {
    $rejected = $false
    try { $null = Get-PassedInstrumentationTestCount $output } catch { $rejected = $true }
    if (-not $rejected) { throw 'Invalid instrumentation output was accepted.' }
}
Write-Output 'Instrumentation result checks passed: 2 valid and 10 invalid outputs.'
