# Optional public resources

Two APK contents are supported with the same application ID and runtime:

- `-PresourceBundle=full` (default): all Wanxiang core tables, LTS grammar, Mini INT8 and Chinese handwriting.
- `-PresourceBundle=lite`: all Wanxiang core tables; LTS, Mini and handwriting download on explicit user request.

The reduced ONNX runtime remains bundled in both; only model data is optional.
No native library, script or executable is downloaded. All installed features run offline.

## Prepare and publish

From the repository root:

```powershell
python tools/resources/prepare-mini.py
python tools/resources/prepare.py
python tools/resources/publish.py
```

Preparation verifies source files and writes deterministic ZIP files under ignored
`build/public-resources/`. Review and commit `tools/resources/catalog.json` before
publishing. The publisher uses `GH_TOKEN`, `GITHUB_TOKEN` or the Git credential
helper without displaying credentials. It uploads immutable content-versioned
Releases, verifies the server digest and never overwrites existing assets.
It does not commit, merge, push code or publish an APK.

For first publication, `prepare-mini.py` downloads only the pinned UER source inputs,
exports/quantizes with the reviewed host tools, and checks the resulting graph and
vocabulary against the already-reviewed catalog. It does not read a resource Release.
Use Python 3.11+ with CPU PyTorch 2.12.0, ONNX 1.19.0, ONNX Runtime 1.26.0,
ml_dtypes 0.5.3 and NumPy 2.2.6 (see the publishing workflow for installation).
Prepare dictionary and handwriting inputs with their existing scripts first.
An altered export or ZIP must fail comparison; review the model and catalog together
before dispatching publication. `fetch-mini.py` is only a convenience for restoring
an already-published resource into a developer checkout, never a publication prerequisite.

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

- Mini INT8 and vocabulary: UER roberta-mini-wwm-chinese-cluecorpussmall,
  revision `5e567169018f5cf84cf1d85a2b391c8d51211eda`, Apache-2.0 under the
  existing reviewed UER licensing basis. Companion sources include attribution,
  export scripts and numerical/quality documentation. Restore reviewed host
  build inputs with `python tools/resources/fetch-mini.py`.
