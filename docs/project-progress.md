# Project status

Updated 2026-11-10.

## Current release

[v0.7.2](https://github.com/yixunfei/zero-fish-input/releases/tag/v0.7.2) is the current Debug-signed testing prerelease. It includes the mixed-pinyin and symbol keyboard fixes, dictionary management improvements and opt-in AI additions. The published universal APK passed an in-place installation over v0.7.1 and 56 emulator tests. See [release notes](releases/v0.7.2.md) and [release validation](releases/v0.7.2-validation.md).

## Next verification

- Validate installation and input on a physical ARM device. The existing device run used an API 37 x86_64 emulator.
- Continue measuring the [80 MB APK target](apk-size-optimization.md). The v0.7.2 universal Debug APK is 103,287,409 bytes.
- Run the live-provider cases when explicit temporary test credentials are available.
- Keep system clipboard text import limited to the test-only P1 prototype. See the [clipboard plan](clipboard-product-repair-plan.md) for pending verification and scope.

## Development checks

```powershell
./gradlew.bat :app:assembleDebug testDebugUnitTest privacyCheck :app:lintDebug `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

Keep the current status here. Place transient logs, review drafts and one-off validation evidence in the ignored `.tmp-evidence/` directory. Keep public release evidence with the corresponding [release notes](releases/).
