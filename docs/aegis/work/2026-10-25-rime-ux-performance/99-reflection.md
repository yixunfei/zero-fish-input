# Rime startup and UX performance - Reflection

- Verified the actual generation checksum contract with device evidence rather than accepting generic host librime artifacts. Source identity separators and the generation-specific dictionary header were decisive.
- Preserved a single deployment owner, worker-owned native compilation, conversion-before-READY, fallback and custom-generation recovery. Lifecycle tests did not justify a speculative `RimeRuntime` rewrite.
- Rejected misleading incremental APK-size evidence after inspecting ZIP offsets; clean packaging establishes a 24.36 MiB cost. Final review also found and regression-tested duplicate base/shared archive caches, bounded to app-owned recoverable data.
- Evidence separates exact APK hashes, cold and warm measurements, native-key and real-window latency, and emulator-only boundaries. The lost original incident remains `deepest-confirmed-root-unknown`; this work establishes a sufficient cold-compilation mechanism and tested recovery, not the original complete trigger history.
- No commit, delegation, physical ARM timing, release signing or full-device-suite claim is part of this closeout. Future highest-value performance work is profiling real-window p95 dispatch/candidate/render tails with a comparable repeated baseline.

Method Pack output does not grant completion authority.
