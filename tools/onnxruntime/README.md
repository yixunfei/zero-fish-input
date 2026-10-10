# Reduced ONNX Runtime

Build tools run only on the development host. The app never downloads executable
libraries. `build.py` fixes the upstream commit, verifies SHA-256 dependency
archives, builds CPU kernels and packages all three ABIs into an ignored local
Maven AAR. Java classes and notices come from the hash-pinned upstream 1.26.0
AAR. JNI is compiled from the same fixed source with CPU-only provider definitions
and undefined-symbol link checks, matching the rebuilt C API revision. The source checkout must be unmodified.

Windows build from the repository root (substitute local SDK/NDK/Ninja paths):

```powershell
python -m venv build/onnx-build-env
build/onnx-build-env/Scripts/python.exe -m pip install onnx==1.19.0 onnxruntime==1.26.0 numpy==2.2.6
build/onnx-build-env/Scripts/python.exe tools/onnxruntime/build.py --sdk D:/env/AndroidSDK --ndk D:/env/AndroidSDK/ndk/28.2.13676358 --ninja D:/env/AndroidSDK/cmake/3.22.1/bin/ninja.exe --package
```

Use CMake 3.28 or newer on PATH. The reviewed NDK is 28.2.13676358. Builds are
serial by ABI with bounded compiler jobs (`--jobs 6` by default). `--abi` supports
individual rebuilds; `--package` requires all three matching verified outputs.
Gradle fails if the local AAR is absent, changed or stale against the operator list.

`runtime.lock.json` is the reviewed source-controlled trust root: it pins the AAR,
operator config, source revision and both native libraries for every supported ABI.
The packager, variant-specific Gradle verification and APK verification consume
this lock. Generated `verification.json` is diagnostic output, never an expected-hash
authority. Rebuilds with a different toolchain/host may produce different bytes;
they fail closed and require independent binary/numerical review and a reviewed
lock update. Build tools never rewrite the lock to accept their own output.

`operators.py` regenerates the operator list from hash-pinned Mini/handwriting
graphs and CPU optimization levels using the pinned host runtime. Run it only
when reviewing a model/runtime change; inspect the resulting list, rebuild all
ABIs and run native numerical and handwriting tests. Host optimization is not
proof of all device graph paths, so device validation remains mandatory.

Source models are the original ONNX files, not the host-optimized generated
files. They are not reformatted or requantized. Runtime graph optimizations stay
enabled; fusion kernels are explicitly included. Type-level pruning is not used.

The upstream source and generated binaries stay under `build/`. Distribute source
revision, this recipe, locks and MIT/third-party notices alongside built packages.
