# Public dictionary validation (2026-10-09)

## Delivered behavior

- Bundled Wanxiang v18.1.1: all 16 core tables and the simplified Chinese LTS
  grammar model, with reviewed source and normalized-file SHA-256 values.
- Foreground source browsing and bounded downloads for Wanxiang, Rime Ice,
  fcitx5-pinyin-zhwiki, QQ and Sogou. QQ/Sogou expose categories, pagination and
  dictionary details without requiring a URL.
- Bounded format conversion, deduplication, atomic installation, enable/disable,
  removal and restoration to bundled dictionaries. Installed data works offline.
- Weekly workflow proposes source-lock updates through a review PR. It does not
  merge updates or publish APKs. Repository Actions PR creation must be enabled.

## Automated checks

From the repository root in PowerShell:

```powershell
python tools/dictionaries/prepare.py
python tools/dictionaries/verify.py
./gradlew.bat testDebugUnitTest :engine-dictionary:test privacyCheck :app:lintDebug `
  :app:assembleDebug :app:assembleDebugAndroidTest -PrequireRime=true --no-parallel
```

The preparation and hash checks, 530 Android-module JVM tests, standalone
dictionary parser tests, privacy checks, Lint and Debug builds passed. Real QQ
and Sogou samples were also parsed locally. Native libraries were built and
packaged for arm64-v8a, armeabi-v7a and x86_64. APK inspection confirmed all 18
public data files, including the 398,309,420-byte grammar model.

Final three-ABI Debug and unsigned Release builds, Lint and privacy checks also
passed with `-PrequireRime=true`. Release merged manifests retain disabled backup
and non-debuggable application settings; dictionary management is not exported.

On the x86_64 emulator, public-store tests covered deduplication, invalid
replacement rollback, interrupted import, publication signals, disable and
removal. Native tests covered full pinyin, nine-key pinyin and double pinyin,
plus installation, candidate lookup and removal of a synthetic dictionary.
The readiness test also exercised missing-data failure and controlled fallback.
The final store/native regression passed three tests in 85.558 seconds.

## UI and live-source checks

The management page was inspected in portrait and landscape, light and dark
themes, and a reduced 720 x 1280 viewport. Content wraps and scrolls; system bars
do not overlap the page. Emulator display settings were restored afterwards.

A live QQ/Sogou category-to-detail-to-download-to-parser device check passed.
Later network retries showed intermittent QQ failures and GitHub data-download
timeouts; GitHub catalog listing succeeded. These live checks depend on upstream
availability and are not represented as passing all source downloads. Pinned
build-time source/model preparation succeeded on the development host.

## Remaining external validation

- ARM device execution has not been performed; ARM native build/package checks
  passed, while instrumentation ran on x86_64.
- The weekly workflow is committed and enabled but has not been executed
  on GitHub. Actions PR creation is enabled. Review source licensing, hashes, native behavior and package size
  before merging future update PRs.
- QQ/Sogou website changes and unrecognized binary layouts fail closed and can
  require adapter updates. Optional downloads are not redistributed in the APK.
- Release artifacts are unsigned and are not publishable packages.

See [ADR 0020](adr/0020-public-dictionaries.md) for data and networking boundaries.
