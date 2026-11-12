function Read-InstrumentationResult {
    param([AllowEmptyString()][string]$Output)

    $summaries = [regex]::Matches($Output, '(?m)^OK \(([1-9][0-9]*) tests?\)\s*$')
    $completion = [regex]::Matches($Output, '(?m)^INSTRUMENTATION_CODE: (-?[0-9]+)\r?$')
    if ($summaries.Count -ne 1 -or $completion.Count -ne 1 -or $completion[0].Groups[1].Value -ne '-1' -or
        $Output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed') {
        throw 'Device regression tests failed or did not execute any tests.'
    }

    # Only AndroidJUnitRunner status bundles describe test outcomes. Individual
    # tests also send status code 0 with timing or other fixture measurements.
    $counts = @{ '1' = 0; '0' = 0; '-3' = 0; '-4' = 0 }
    $runnerStatus = $false
    foreach ($line in ($Output -split '\r?\n')) {
        if ($line -ceq 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner') {
            $runnerStatus = $true
        } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: (-?[0-9]+)$') {
            if ($runnerStatus) {
                $code = $Matches[1]
                if (-not $counts.ContainsKey($code)) { throw 'Device regression tests reported an unsuccessful result.' }
                $counts[$code]++
            }
            $runnerStatus = $false
        }
    }

    $passed = $counts['0']
    $skipped = $counts['-3'] + $counts['-4']
    $reported = [int]$summaries[0].Groups[1].Value
    if ($passed -eq 0 -or $runnerStatus -or $counts['1'] -ne $passed + $skipped -or
        $reported -ne $passed + $counts['-4']) {
        throw 'Device regression tests had no passing tests or incomplete results.'
    }
    return @{ Passed = $passed; Skipped = $skipped }
}
