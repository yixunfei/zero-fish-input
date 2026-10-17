# Package compression validation (2026-10-09)

## Physical APK sizes

All figures are bytes on disk. ARM64 Debug deliveries are normally installable,
debug-signed APKs. Release artifacts have no signing key and remain unsigned.

| Variant | Previous | Current | Reduction |
| --- | ---: | ---: | ---: |
| ARM64 lite Release | 90,295,023 | 45,092,650 | 45,202,373 (50.1%) |
| ARM64 full Release | 489,922,193 | 57,112,683 | 432,809,510 (88.3%) |
| ARM64 lite Debug | 103,821,582 | 52,479,589 | 51,341,993 (49.5%) |
| ARM64 full Debug | 506,188,460 | 64,693,446 | 441,495,014 (87.2%) |

The ARM64 Full Release may exceed 50,000,000 bytes because the reviewed Emoji
WebP artwork remains bundled for offline display. Deliveries and SHA-256 receipts
are in `build/delivery/`.

The optimized packages keep approximately 19.3 MB of XZ Wanxiang tables,
4.8 MB of native libraries and 4.2 MB of code/resources/overhead. Full adds
Mini only; LTS and handwriting are no longer packaged, while Emoji WebP artwork
remains bundled.

All 16 tables retain exact original bytes and weights. XZ payload totals
19,319,192 bytes versus 26,465,499 bytes in the previous Release ZIP. Reduced
ARM64 ONNX is 10,142,032 bytes uncompressed (previously 27,408,600), approximately
3.5 MB with DEFLATE. Mini data is omitted only from lite. Emoji catalog and
artwork bytes remain bundled in both APKs.

## Implementation and tests

- Native libraries are DEFLATE-compressed; Android extracts them at installation.
  This reduces transfer size but can increase total installed storage compared
  with directly mapped libraries. Release stays non-debuggable and backup-disabled.
- The APK verifier checks every decompressed table hash, no duplicate entries,
  full/lite resource sets, no double-compressed XZ, physical ZIP overhead and the
  exact reduced runtime/JNI hashes. Both ARM64 Release and both ARM64 Debug
  deliveries passed verification; the Debug signatures passed verification.
- 542 Android-module JVM tests and 57 pure JVM tests passed, including XZ
  corruption, truncation, excess memory/output, trailing data and cancellation;
  Mini graph restrictions; resource-change retry and stale ranking rejection.
- `privacyCheck`, app Debug Lint and real-Rime full/lite builds passed.
- x86_64 instrumentation passed resource install/hash/rollback/removal tests,
  actual Mini numerical inference, handwriting, native grammar and input after
  removal (four tests). A separate opt-in test downloaded Mini from public GitHub
  Releases through the production Android transport, verified/installed it,
  compared numerical results and removed it successfully.
- Full-model tests passed Mini numerical bounds, candidate tap/Space/Enter/
  punctuation routing, IME visible-selection commit, constructed handwriting
  and the independent 20-character handwriting quality floor (13 top-1, 18 top-16).
  Mini p95 on the final x86_64 test was 8.55 ms; these are emulator measurements.
- A fresh isolated deployment without any optional resources passed. It restored
  all 16 tables (70,601,147 bytes), converted Chinese input and reused unchanged
  files on restart. First deployment plus Rime compilation took 42.6 seconds;
  reuse plus native initialization took 0.85 seconds. This includes native
  dictionary compilation, not just XZ decompression, and runs on the engine worker.

## Issues caught during verification

The upstream prebuilt JNI referenced an NNAPI entry point absent from CPU-only
builds. JNI is now built from the same fixed source and configuration, with
undefined-symbol link checks and 16 KiB page alignment. Java classes/notices still
come from the hash-verified upstream AAR.

A fresh lite deployment exposed unconditional native grammar loading that was
masked by previous process state. JNI now tolerates an absent optional grammar;
a dangling managed link still fails. The fresh-directory regression checks this.
Model tests now explicitly enable Mini and restore prior enablement, isolating
them from resource-removal tests. One UI commit test timed out in a combined run;
it passed in isolation and in the final combined cold-deployment/model run.

## Publication and remaining limits

Mini archive `mini-int8-376001ee28f93dcb.zip` is published with companion source
and license material in release `resources-376001ee28f93dcb`. Its 12,020,028-byte
archive SHA-256 is `297791c128614ea4528c97f64851192c041cb0b7a0062709b973b02c3c0b2bdf`.
The public endpoint and server digest were verified before catalog promotion.
Weekly reviews remain PR-only; no merge or APK publication is automatic.

No ARM physical device was available: both ARM ABIs were compiled and packaged,
but native execution used x86_64. Release signing is intentionally unconfigured.
The large LTS/handwriting integration used exact release ZIP fixtures; Mini's
network test does not establish their large-download reliability. New graphs need
an app/runtime review because the executable kernel set is deliberately restricted.
This turn changed only resource text/rows; theme/rotation visual inspection from
the prior resource-settings delivery was not repeated.

See [ADR 0022](adr/0022-compressed-resources-and-reduced-runtime.md) and
[runtime build instructions](../tools/onnxruntime/README.md).
