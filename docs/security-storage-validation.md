# Security storage validation

Date: 2026-09-21

## Scope and unchanged boundaries

This batch implements the approved storage reliability and test coverage work.
Authentication still uses the existing system prompt and application-layer
one-use grant. Production key aliases, AAD, AES-GCM envelope format 1 and vault
JSON formats are unchanged. No migration, runtime dependency, permission, exported
component or network path was added.

## Reproduced failures and fixes

Before fixes, eight of the initial nine device regressions failed. Two further
atomic-file regressions each failed before their corresponding fix. These ten
failing cases cover the following boundaries; the buffer-wipe case already passed.

| Boundary | Observed failure | Current behavior |
| --- | --- | --- |
| Lost key | Reading ciphertext created a replacement key before failing | Read requires the existing key and preserves ciphertext |
| Unreadable existing file | A directory at the file path was treated as empty | Existing unreadable base/backup fails closed |
| Atomic delete | Nonempty backup directory survived without an error | Remaining base, backup or new file causes failure; key deletion is still attempted |
| Atomic promotion | Failed rename was reported as a successful write | Commit checks the base file and surviving new/backup files |
| Backup cleanup | An unremoved backup was reported as a successful write | Remaining backup causes failure |
| Vault clear | Body failure skipped index deletion; failed clear allowed access | Both resources are attempted; the current instance blocks access until clear retry succeeds |
| Corrupt vault JSON | Parser exception included a constructed field value | Generic error without parser cause; original storage and buffer wiping preserved |
| Metadata vs clear | A stale load returned labels and recreated the index | Generation rechecked after loading and index persistence |
| Remove vs clear | A stale removal restored other cleared entries | Generation checked before/after writes; compare-and-set cannot override clear |
| Index write failure | Cached rows still represented deleted entries | Stale summaries hidden until authenticated reconciliation |

Additional tests cover queued management operations and real two-thread,
latch-controlled clear races. Management Save captures its generation before
authentication. Metadata/removal capture it before queuing. Grant lifetime tests
verify that callers cannot extend 30 seconds and eight concurrent consumers have
one winner. Independent JCE readers/writers verify the unchanged envelope layout;
wrong keys, AAD, IV, ciphertext, tags and malformed headers fail closed.

## Executed verification

Environment: Windows, Android API 36 x86_64 emulator `ZeroInputModelApi36`, real
Rime sources with `-PrequireRime=true`. Only public fixtures, temporary test files
and isolated test Keystore aliases were used.

- 216 JVM/Debug unit tests passed, with zero failures, errors or skips: app 53,
  engine-dictionary 8, engine-english 5, engine-rime 19, ime-core 87, ime-ui 9,
  language-pack 12, model-scoring 2, security 10, user-data 11. `engine-api:test`
  has no test sources. Release-variant duplicates are not included.
- 44 device tests passed: SecurityStorageBoundaryTest 6,
  SecureClipboardVaultReliabilityTest 7, ClipboardImportVaultTest 6,
  EncryptedUserStoreTest 4, ExpressionEncryptedStoreTest 2,
  UserLexiconBoundaryTest 13, SecurePasteConsentTest 6.
- One SecurePasteReturnTest was skipped because its required test PIN argument
  was not configured. Raw instrumentation returned assumption status `-4`;
  its printed `OK (1 test)` is not counted as a pass above.
- Debug app and instrumentation APK builds, root `privacyCheck` (including
  Debug/Release merged manifests), and app/security/user-data Debug Lint passed.

From the repository root:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest `
  :engine-api:test :engine-english:test :engine-dictionary:test privacyCheck `
  :app:lintDebug :security:lintDebug :user-data:lintDebug -PrequireRime=true `
  "-Pandroid.injected.build.abi=x86_64" --no-parallel --console=plain --quiet

$storageTests = @(
  'dev.zeroinput.ime.SecurityStorageBoundaryTest'
  'dev.zeroinput.ime.clipboard.SecureClipboardVaultReliabilityTest'
  'dev.zeroinput.ime.clipboard.ClipboardImportVaultTest'
  'dev.zeroinput.ime.EncryptedUserStoreTest'
  'dev.zeroinput.ime.ExpressionEncryptedStoreTest'
  'dev.zeroinput.ime.UserLexiconBoundaryTest'
  'dev.zeroinput.ime.clipboard.SecurePasteConsentTest'
)
./tools/test-input-experience.ps1 -Serial emulator-5554 -TestClass ($storageTests -join ',')
```

Use the actual attached emulator/device serial when repeating the command.
Local evidence: `build/storage-before.log`, `build/storage-promotion-before.log`,
`build/storage-backup-before.log`, `build/storage-validation-final.log`,
`build/storage-device-final.log`, `build/storage-credential-skip.log` and module
`build/test-results` XML. Generated logs/APKs are not source artifacts.

## Limits and remaining acceptance

- API 26 and ARM devices were unavailable for this run. AtomicFile has differing
  platform implementations; directory-collision tests verify API 36 behavior.
  Physical biometric prompts, actual permanently invalidated keys and lock-screen
  transitions remain untested. Explicit test grants validate repository policy,
  not the platform's authentication strength.
- Body and index remain separately atomic files. Repair/deletion-pending state
  exists only in the current vault instance. Process death after partial failure
  can leave a stale body-free index requiring authenticated reconciliation, or
  require explicit clear retry. No cross-file transaction, durable rollback or
  power-loss erasure guarantee was introduced. Commit checks detect observable
  rename/delete failures, not every filesystem sync failure.
- Owned byte buffers are wiped. JSON strings and JCE/platform internals cannot
  provide a verifiable complete memory-erasure guarantee.
- Release APK and three-ABI packaging were not run: no native, ABI, dependency,
  manifest or release configuration changed. This remains a Debug validation
  artifact; physical performance and production signing are outside this batch.
- Follow-up device acceptance should use synthetic data to exercise successful
  and cancelled credential return, biometric enrollment/key invalidation, API 26
  failed-write/clear retry, and process termination between body/index writes.
  Never reuse a personal vault or real clipboard content for these checks.
