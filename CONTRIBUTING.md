# Contributing

Thanks for contributing to zero fish input. Read [AGENTS.md](AGENTS.md),
[the architecture](docs/architecture.md) and [the threat model](docs/threat-model.md)
before changing code. Project priorities are privacy, correct input, responsive
offline operation and maintainable module boundaries.

## Issues and pull requests

- Search existing issues before opening a report. Include Android version, device
  model, app version, keyboard mode, reproduction steps and expected behavior.
- Use invented public examples. Never attach personal input, dictionaries, secure
  snippets, keys, app data directories or unreviewed system logs.
- Report security vulnerabilities privately using [SECURITY.md](SECURITY.md).
- Discuss changes to data formats, permissions, engine contracts or privacy
  behavior before implementation. Keep each pull request focused.
- Add regression tests for fixes and negative tests for imports, authentication,
  encryption, deletion and asynchronous lifecycle changes.
- Keep visible strings in both Chinese and English resources. Use the existing
  Android Views, Material and ViewBinding patterns.

## Build and verification

Follow [README.md](README.md) for the Windows toolchain and pinned Rime bootstrap.
From the repository root, run:

```powershell
./tools/bootstrap-rime.ps1
./gradlew.bat :app:assembleDebug testDebugUnitTest privacyCheck :app:lintDebug `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
```

Use `./tools/test-input-experience.ps1 -Serial <device-serial>` for connected
device tests. It installs test APKs and temporarily changes the selected keyboard,
restoring it on exit. Use a development device with synthetic data.

For all ABIs and distribution checks, run `./tools/package-test-apk.ps1` and
`./gradlew.bat :app:assembleRelease -PrequireRime=true --no-parallel`.
Release outputs are intentionally unsigned. Do not commit signing keys.

Describe the behavior changed, verification performed and any tests not run in
your pull request. Do not weaken privacyCheck, Lint, tests or native requirements
to make a build pass. No runtime networking, telemetry or system clipboard access
may be added, including optional or debug-only paths.

## Assets and third-party code

The original project artwork is `logo.jpg`. Regenerate the Android launcher
artwork on Windows with `./tools/generate-launcher-icon.ps1`; inspect its framing
on a launcher before submitting. Do not replace third-party license notices.

New dependencies require a clear purpose, pinned versions, compatible licensing
and an offline/privacy assessment. Contributions to project code are made under
the repository's Apache-2.0 license. Third-party data retain their original terms.
