[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/InstrumentationResult.ps1')

$runner = 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner'
$start = "$runner`nINSTRUMENTATION_STATUS_CODE: 1"
$passed = "$runner`nINSTRUMENTATION_STATUS_CODE: 0"
$ignored = "$runner`nINSTRUMENTATION_STATUS_CODE: -3"
$assumption = "$runner`nINSTRUMENTATION_STATUS_CODE: -4"
$failed = "$runner`nINSTRUMENTATION_STATUS_CODE: -2"
$measurement = "INSTRUMENTATION_STATUS: fixture_elapsed_ms=42`nINSTRUMENTATION_STATUS_CODE: 0"
$complete = 'INSTRUMENTATION_CODE: -1'
$onePassed = "$start`n$passed`nOK (1 test)`n$complete"
$mixed = "$start`n$measurement`n$passed`n$start`n$ignored`n$start`n$assumption`nOK (2 tests)`n$complete"
$valid = @(
    @{ Output = $onePassed; Passed = 1; Skipped = 0 }
    @{ Output = $mixed.Replace("`n", "`r`n"); Passed = 1; Skipped = 2 }
)
foreach ($fixture in $valid) {
    $result = Read-InstrumentationResult $fixture.Output
    if ($result.Passed -ne $fixture.Passed -or $result.Skipped -ne $fixture.Skipped) {
        throw 'Unexpected instrumentation test count.'
    }
}
$invalid = @('', "OK (0 tests)`n$complete", "OK (00 tests)`n$complete", "prefix OK (1 test)`n$complete",
    "$onePassed`nOK (2 tests)", "$onePassed`nFAILURES!!!",
    "$onePassed`nINSTRUMENTATION_FAILED: runner", "$onePassed`nINSTRUMENTATION_ABORTED: runner",
    "$onePassed`nINSTRUMENTATION_RESULT: shortMsg=Process crashed.", $complete,
    "$start`n$measurement`n$assumption`nOK (1 test)`n$complete",
    "$start`n$ignored`nOK (1 test)`n$complete", "$start`n$passed`nOK (1 test)",
    "$start`n$passed`nOK (1 test)`nINSTRUMENTATION_CODE: 0", "$onePassed`n$complete",
    "$start`n$passed`n$start`nOK (1 test)`n$complete", "$start`n$passed`nOK (2 tests)`n$complete",
    "$start`n$passed`n$start`n$failed`nOK (1 test)`n$complete")
foreach ($output in $invalid) {
    $rejected = $false
    try { $null = Read-InstrumentationResult $output } catch { $rejected = $true }
    if (-not $rejected) { throw 'Invalid instrumentation output was accepted.' }
}
Write-Output "Instrumentation result checks passed: $($valid.Count) valid and $($invalid.Count) invalid outputs."
