# Project status

Updated 2026-11-11.

## Current release

[v0.7.3](https://github.com/yixunfei/zero-fish-input/releases/tag/v0.7.3) is the current Debug-signed testing prerelease. It includes private temporary input, offline Han shape lookup, keyboard toolbar improvements and public dictionary import fixes. See [release notes](releases/v0.7.3.md) and [release validation](releases/v0.7.3-validation.md).

## Public dictionary import

The catalog now distinguishes download and import from visiting the source
website. QQ resolves the selected identifier directly and upgrades only the
known legacy CDN package URL to HTTPS. Sogou matches the selected item and
keeps its required download parameters. Oversized GitHub files are omitted.

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest `
  :engine-dictionary:test :language-pack:testDebugUnitTest privacyCheck :app:lintDebug `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

The build and checks passed. Isolated API 37 emulator tests passed with local
import fixtures and with real QQ, Sogou and Chinese Wikipedia source files.
The online cases require the explicit instrumentation argument
`-e livePublicDictionary true`.

## Keyboard tools

Private copy uses a 48 dp icon with the existing accessibility label.
The primary toolbar exposes private temporary input and the secure clipboard.
More tools places additional actions ahead of the primary actions.
Debug APKs, unit tests, privacyCheck and Lint passed with `-PrequireRime=true`.
The API 37 x86_64 emulator verified direct access to both privacy actions at
320 dp portrait width, rotation and light/dark layout coverage.

## Private temporary input and Han shape lookup

The confirmed default toggle and clipboard append boundary are recorded in
[ADR 0036](adr/0036-private-temporary-input.md). The private panel starts on
supported ordinary text editors after authentication enables the default.
Composition and candidates stay visible while the draft masks text before
the last three characters. The full-display toggle lasts for the current draft.
Save and close writes an encrypted item. Save and submit also writes a copy
by default, controlled by the setting in Privacy. Submit only goes directly
to the editor.

Shape lookup uses a build-verified index of 41,874 characters. The panel accepts
a radical or a pinyin seed, shows ranked matches in a scrolling candidate list and commits only the
selected result. The pinned sources and generation procedure are recorded in
[Third-party notices](../THIRD_PARTY.md#offline-han-shape-lookup).

The API 37 x86_64 emulator passed 57 release regression tests after upgrading
from v0.7.2, including the real system PIN toggle, private pinyin candidate
selection, masked and full draft display, length-limit handling, submit-only
with copy retention enabled, authenticated paste with separate confirmation,
and scrolling and selection through shape matches. See
[release validation](releases/v0.7.3-validation.md).
The new flows are in `PrivateDraftAndShapeIntegrationTest`. Run the private
draft flow only on a disposable emulator with its fixed test PIN and the
explicit `-e clipboardTestPin 2468` argument.

## Next verification

- Validate installation and input on a physical ARM device. The existing device run used an API 37 x86_64 emulator.
- Continue measuring the [80 MB APK target](apk-size-optimization.md) for the ARM64 package. The v0.7.3 ARM64 Debug split is 92,380,591 bytes.
- Run the live-provider cases when explicit temporary test credentials are available.
- Keep system clipboard text import limited to the test-only P1 prototype. See the [clipboard plan](clipboard-product-repair-plan.md) for pending verification and scope.

## Development checks

```powershell
./gradlew.bat :app:assembleDebug testDebugUnitTest privacyCheck :app:lintDebug `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

Keep the current status here. Place transient logs, review drafts and one-off validation evidence in the ignored `.tmp-evidence/` directory. Keep public release evidence with the corresponding [release notes](releases/).
