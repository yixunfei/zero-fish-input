# APK size optimization

Date: 2026-11-05. Status: lossless improvements verified; strict size target not met.

## Goal and scope

Target a physical APK size of at most **80,000,000 bytes** (80 decimal MB,
76.29 MiB). Prioritize the ARM64 full package; measure Debug, unsigned Release
and universal packages separately. A size estimate is not an accepted artifact.

Preserve all sixteen exact Wanxiang source tables and weights, all 3,972 Emoji
images, bundled Mini INT8, reviewed default prebuilt Rime artifacts and offline
input. LTS/handwriting keep their existing explicit-download boundary. Do not
change personal-data formats, permissions, network paths or production signing.
Do not trade the accepted cold-start acceleration or decoder bounds for size.

Existing uncommitted assistant and keyboard work must be preserved. This document
is the detailed work plan; [project-progress.md](project-progress.md) remains the
authoritative progress entry.

## Measured baseline

Historical delivered artifacts, inspected on 2026-11-05:

| Artifact | Physical bytes | Interpretation |
| --- | ---: | --- |
| 20261102-131603 ARM64 full Debug | 113,717,612 | Current delivered AI expansion build |
| 20261031-100310 universal full Debug | 168,017,805 | Earlier build; not an identical-source ABI comparison |
| v0.7.0 ARM64 full Debug | 65,103,499 | Historical package without default prebuilt assets |

ARM64 compressed payload: prebuilt Rime 25,545,760 bytes; Rime source tables
19,319,192; Emoji 16,003,194; Mini 12,213,601; Rime native library 21,988,738;
C++ runtime 2,613,806; ONNX runtime 3,895,115; remaining code/resources about
10.4 MB. ZIP overhead is about 0.84 MB, not the main cause of growth.

On extracted copies, the installed pinned NDK's `llvm-strip --strip-unneeded`
reduced Rime from 67,130,936 to 6,291,872 uncompressed bytes and libc++ from
9,236,352 to 1,253,544. DEFLATE experiments suggest 22,398,726 bytes of ARM64
APK savings, or 66,433,454 bytes across three ABIs. These are payload experiments,
not built/signed APK measurements. Raw libraries remain available in native
build outputs for debugging.

## Work stages and acceptance

1. Restore normal native symbol stripping. Align app packaging with engine-rime's
   pinned NDK, inspect final ELF sections, and reject retained debug/symbol tables.
   Preserve native caches; repair obsolete local cache links without deleting
   their targets. Build a new ARM64 full package and record its exact contents.
2. Measure further lossless opportunities. Retain the existing 16 MiB XZ decoder
   memory limit and original resource hashes. Evaluate changes on copies first;
   reject negligible savings, slower startup or increased runtime memory.
   Existing R8/resource shrinking remains enabled for Release. Any retained
   compression change must pass cold native deployment, conversion and reuse.
3. Add repeatable physical-size/component reports and an explicit 80 MB delivery
   gate. Include bytes, MB/MiB, ABI, SHA-256, debug sections and ZIP slack. Connect
   the gate to packaging before copying a deliverable. Keep resource, license,
   runtime-integrity and privacy checks intact. Report over-budget variants
   honestly rather than relabelling lite as full.
4. Validate relevant unit/negative tests, privacyCheck, Lint, all three native
   ABIs and full Release packaging with `requireRime=true`. Use an isolated
   emulator for actual offline native input, prebuilt startup and Mini inference;
   do not overwrite the owner's active emulator. Physical ARM validation and
   production signing require owner-managed devices/keys and remain explicit
   limits when unavailable.

If the full ARM64 package remains above budget after useful lossless changes,
record the smallest verified artifact and the remaining gap. Removing Mini,
Emoji/source tables or prebuilt startup acceleration requires a separate product
decision. Existing lite can be measured as an alternative, not substituted for
the agreed full-package target.

## Verified results

| Full package | Physical bytes | Decimal MB | MiB | Strict 80 MB target |
| --- | ---: | ---: | ---: | --- |
| ARM64 Debug, signed test delivery | 91,601,352 | 91.601 | 87.358 | Exceeded |
| ARM64 unsigned Release | 83,248,958 | 83.249 | 79.392 | Exceeded by 3,248,958 bytes |
| ARMv7 unsigned Release | 82,587,674 | 82.588 | 78.762 | Exceeded |
| x86_64 unsigned Release | 83,465,578 | 83.466 | 79.599 | Exceeded |
| Universal unsigned Release | 92,487,766 | 92.488 | 88.203 | Exceeded |

The new ARM64 Debug is 22,116,260 bytes (19.45%) smaller than the last delivered
Debug, including subsequent keyboard changes. This is a historical delivery
comparison, not a same-source Debug/Release compression ratio.

App NDK alignment restored normal symbol stripping. A subsequent Debug build
still physically occupied 113,651,367 bytes while its live ZIP payload was only
90,987,953 bytes: 22,663,414 bytes of framing/obsolete space. Replacing DEX with
an experimental minified build grew that gap further. Resetting only generated
Debug packaging state brought ZIP overhead below 0.84 MB. Native caches and
`outputs/test-apk/` deliveries are excluded from reset, and links are rejected.

Stronger prebuilt compression within the same 8 MiB LZMA dictionary saved only
6,720 bytes; delta and literal/position experiments made it larger. They were
not adopted. Extra Debug R8 shrinking caused Android test-runner loading failure
(`kotlin.LazyKt` missing) and was reverted. Final Debug remains normally
debuggable and instrumentation-compatible; Release retains its existing R8 and
resource shrinking. Diagnostic/failed experimental APKs are not deliveries.

No tested bounded lossless change bridged the remaining 3.25 MB Release gap.
Further work can profile solid artwork encoding or shared asset storage, with
decode-memory/latency and exact-image acceptance before changing runtime formats.
The existing lite resource delivery could remove bundled Mini, but that would
change the agreed full-package target and requires a separate owner decision.
Deleting default prebuilt artifacts would regress accepted first-input latency.

## Verification and artifacts

- Full JVM inventory: 1,934 cases, 1,930 passed, four live-provider skips; some
  unchanged Gradle tasks reuse valid results. Python gate/integrity tests: 13
  passed. PowerShell locator/reset tests preserve unrelated directories and
  reject linked outputs. Privacy checks include merged Debug/Release manifests;
  Lint and all three native ABI/Release builds passed with `requireRime=true`.
- Fifteen final-source API 37 emulator tests passed for reviewed identity,
  cold/reuse/corruption deployment, native pinyin, Mini/editor commits and keyboard
  continuity. Cold deployment: 6,751 / 7,225 / 7,363 ms; reuse: 730 / 746 / 778 ms.
  The isolated task-owned emulator was stopped after acceptance.
- Every delivered Debug/Release APK retains all sixteen original tables, exact
  prebuilt artifacts, Mini and 3,972 image hashes from the reviewed Emoji lock.
  All Release APKs are non-debuggable. The optional full Pillow decode audit
  could not run because host Python lacks PIL; no artwork was changed, and all
  final image bytes were verified against the reviewed SHA-256 values instead.
- Strict 80 MB rejection is verified. Packaging at an explicitly selected
  95,000,000-byte test-delivery limit passed signature/ABI/permission/resource
  checks; report and provenance retain `targetBytes=80000000`, `targetMet=false`.
  Physical ARM/OEM execution and production signing were not available.

Run the gate tests from the repository root:

```powershell
python -m unittest discover -s tools/resources -p 'test_*.py'
./tools/test-test-apk-artifact.ps1
```

Full ARM64 signed Debug:
`app/build/outputs/test-apk/20261105-191408/zero-fish-input-0.7.1-debug-arm64-v8a.apk`.
SHA-256: `008c57dc4906937a44be85712ccb52d706ba2d191635c2a669a44b7759289f43`.
APK-SIZE.json, BUILD-PROVENANCE.json, notices and corresponding source archives
are beside the APK. The artifact was produced by:

```powershell
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 95000000
```

Unsigned Release copies, physical-size reports and SHA-256 checksums are under
`build/apk-size-analysis/release-full/`. Reports are generated by
`tools/resources/apk_size.py`; final contents are checked by
`tools/resources/verify-apk.py`. Size and resource checks cannot be bypassed by
Python `-O`. `--report-only` measures without accepting a deliverable. A custom
`--max-bytes` changes only that invocation's delivery limit, not the fixed target.

Evidence: `.tmp-evidence/apk-size-arm64-delivery.log`,
`apk-size-all-abi-final.log`, `apk-size-final-platform-build.log`,
`apk-size-final-platform.log`, `apk-size-python-tests.log`, and
`build/apk-size-analysis/artifact-validation.json`. Failed/experimental logs
remain diagnostic only. The original historical measurements above are retained.

## References

- [Accepted resource delivery](adr/0021-optional-public-resources.md)
- [Bounded compression and reduced runtime](adr/0022-compressed-resources-and-reduced-runtime.md)
- [Accepted default prebuilt artifacts](adr/0027-reviewed-default-rime-artifacts.md)
- [Historical package measurements](https://github.com/yixunfei/zero-fish-input/blob/379a463d8e5e2207815cd289c5c532d7211b99c2/docs/package-compression-validation.md)
- [Android APK size guidance](https://developer.android.com/topic/performance/reduce-apk-size)
- [Separate native debug symbols](https://developer.android.com/build/include-native-symbols)
