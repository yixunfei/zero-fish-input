# ADR 0022: Compressed tables and reduced model runtime

Status: accepted (user confirmation, 2026-10-09)

## Decision

Native libraries use Android's compressed APK packaging and installation-time
extraction. This reduces the APK transfer size; it does not imply lower total
installed storage. ABI-specific packages remain required.

All 16 Wanxiang tables remain bundled. Each table uses one XZ stream (preset 6,
CRC64), with original and compressed lengths/hashes. The existing Rime worker
decodes into the unpublished staging generation, then verifies all original
dictionary hashes before activation. Output is bounded to 50 MB per table and
decoder memory to 16 MiB. Trailing/concatenated streams, damage, interruption and
limit violations fail the generation. No parsing or decompression runs on the
IME main thread. Existing verified generations remain available on failure.

Mini INT8 becomes the third supported public resource. Full bundles Mini only;
lite bundles none of Mini, LTS or handwriting. LTS and handwriting are explicit
downloads; reviewed Emoji artwork remains bundled,
licenses, fixed shapes, hashes, worker-owned leases, removal and offline use follow
ADR 0021. Registry format remains unchanged; no personal data migration occurs.
Mini stays default-off. Missing data preserves baseline candidates, and resource
revision changes reject old results and recreate the scorer on its worker.

ONNX Runtime 1.26.0 is built at commit
`8c546c37b43caaca1fa25db430dab94b901cf277`, with only reviewed original and optimized
Mini/handwriting kernels, CPU execution, ML and generation operators disabled.
It retains the ONNX reader and graph optimizer (not the ORT-format-only minimal
runtime). The pinned upstream AAR supplies matching Java classes and notices;
its runtime and JNI libraries are replaced by our three locally built ABI libraries.
No native libraries are fetched or replaced on Android.

Graph hashes are app-supported contracts: a future model requires an app/runtime
review, not just a new remote manifest. The operator list includes kernels created
by optimization/fusion, not only nodes in source models. Native numerical and
handwriting integration tests gate every runtime change. Reproducible host build
instructions, SHA-256 dependency locks and operator configuration are tracked.

The reviewed `tools/onnxruntime/runtime.lock.json` additionally pins the produced
AAR and runtime/JNI hashes for all three ABIs. Packaging, Gradle variant verification
and APK verification use this tracked lock, never generated build-directory stamps.
Toolchain output changes require review; no build updates its own expected hashes.

## Alternatives and consequences

Keeping native libraries uncompressed supports direct APK mapping but costs more
download bytes. XZ introduces a small 0BSD Java decoder and additional first-run
CPU work; it preserves exact dictionary bytes, ranking and coverage. Subsequent
starts reuse the verified deployment. Dictionary weights are not deduplicated.

Deleting ONNX entirely would break downloaded Mini and image handwriting.
Downloading executable libraries violates the pure-data boundary. A general
runtime avoids custom-build maintenance but dominates package size. Reduced
runtime artifacts deliberately fail the build when missing or stale rather than
silently falling back to the larger upstream library.

Emoji catalog, artwork and insertion remain available offline in both packages. All core dictionaries
remain bundled. Full keeps Mini as its sole optional model and no longer carries
the 400 MB grammar or handwriting payload.
