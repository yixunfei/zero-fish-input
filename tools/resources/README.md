# Optional public resources

Two APK contents are supported with the same application ID and runtime:

- `-PresourceBundle=full` (default): all Wanxiang core tables, LTS grammar and Chinese handwriting.
- `-PresourceBundle=lite`: all Wanxiang core tables; LTS and handwriting download on explicit user request.

The short-word Mini INT8 model and its shared ONNX runtime remain bundled in both.
No native library, script or executable is downloaded. All installed features run offline.

## Prepare and publish

From the repository root:

```powershell
python tools/resources/prepare.py
python tools/resources/publish.py
```

Preparation verifies source files and writes deterministic ZIP files under ignored
`build/public-resources/`. Review and commit `tools/resources/catalog.json` before
publishing. The publisher uses `GH_TOKEN`, `GITHUB_TOKEN` or the Git credential
helper without displaying credentials. It uploads immutable content-versioned
Releases, verifies the server digest and never overwrites existing assets.
It does not commit, merge, push code or publish an APK.

The application checks the catalog from this repository's `main` branch only when
requested. Every supported resource has a fixed data contract, version, license,
archive byte length/hash and per-file length/hash. Unsupported file sets fail closed.
Publish resource assets before promoting an updated catalog to `main`.
Keep older releases available for previously installed versions.

## Source attribution and redistribution

- Wanxiang LTS: amzxyz/RIME-LMDG, CC-BY-4.0.
  Source: https://github.com/amzxyz/RIME-LMDG/releases/tag/LTS
  Exact upstream hash and size are in `tools/dictionaries/sources.lock.json`.
- Handwriting image recognition: PaddlePaddle PP-OCRv5 mobile, Apache-2.0,
  model repository revision `ed152b8b495f84de93cda5709d768548a9127622`.
  Source: https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx
  The exported graph and character-table preparation is documented in
  `tools/prepare-handwriting-model.py`.
- Handwriting stroke data: Tegaki 0.3 simplified-light and traditional, LGPL-2.1.
  Source: https://github.com/tegaki/tegaki/releases/tag/v0.3
  Companion source assets include original archives (training XML, models,
  COPYING and build files), transformation scripts and Zinnia algorithm notice.
  Converted feature tables retain the original labels and weights; users can
  rebuild/replace the public data. No private handwriting samples are included.

Resource updates require review of data compatibility, source license, attribution,
hashes and regression results. Weekly dictionary PRs never automatically merge.
