function Get-PassedInstrumentationTestCount {
    param([AllowEmptyString()][string]$Output)

    $summaries = [regex]::Matches($Output, '(?m)^OK \(([1-9][0-9]*) tests?\)\s*$')
    if ($summaries.Count -ne 1 -or
        $Output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed') {
        throw 'Device regression tests failed or did not execute any tests.'
    }
    return [int]$summaries[0].Groups[1].Value
}
