# Source and license distribution

Project source: https://github.com/yixunfei/zero-fish-input

Initial release source: https://github.com/yixunfei/zero-fish-input/tree/v0.1.0

Current release source: https://github.com/yixunfei/zero-fish-input/tree/v0.4.0

The 2026-09-30 input-feature test artifacts are development builds from the
working checkout. The published release link above is not a claim that these
unpublished changes are included in that tag. Preserve this checkout's source
and the packaged corresponding-source archives when distributing a test build.

The project's original source code is available under Apache-2.0. See LICENSE.
Third-party software and data retain their own licenses. See THIRD_PARTY.md,
NOTICE and LICENSES/ for attribution and complete license texts.

The APK includes these notices under assets/licenses/. Release downloads also
provide a separate notices archive. Preserve them when redistributing binaries.

## Corresponding third-party sources

Run tools/bootstrap-rime.ps1 from a project source checkout to retrieve the exact
sources and data used by this build. That script pins full commits and archive
SHA-256 checksums. THIRD_PARTY.md lists the components and licenses.

- librime: https://github.com/rime/librime/tree/1c23358157934bd6e6d6981f0c0164f05393b497
- Luna Pinyin: https://github.com/rime/rime-luna-pinyin/tree/46acf03142c12b5aeed7002675046bf7255eed35
- Rime Essay: https://github.com/rime/rime-essay/tree/0766c929ec3e578c2c80861e988decd3703a1c3d
- OpenCC: https://github.com/BYVoid/OpenCC/tree/e5d6c5f1b78e28a5797e7ad3ede3513314e544b7
- LevelDB: https://github.com/google/leveldb/tree/99b3c03b3284f5886f9ef9a4ef703d57373e61be
- marisa-trie: https://github.com/rime/marisa-trie/tree/0d4e8ab58eec355facf8f65ff11ef811b330e373
- yaml-cpp: https://github.com/jbeder/yaml-cpp/tree/f7320141120f720aecc4c32be25586e7da9eb978
- Boost: https://github.com/boostorg/boost/releases/tag/boost-1.89.0
- Kaomoji data: https://github.com/rimeinn/rime-kaomoji/tree/e66d8f26ee47b4a2a840983b7d71ed69b5ecf756

The unmodified Luna Pinyin and Essay source data are included in
engine-rime/src/main/assets/rime/. Their LGPL-3.0 license also covers the
pinyin-syllables.txt table derived by engine-rime/build.gradle.kts. Modify the
source data and rebuild to replace that table. The original data remain readable
inside the APK. The independent project code retains Apache-2.0.

Gradle dependency coordinates and versions are in gradle/libs.versions.toml.
Android NDK runtime notices are in LICENSES/android-ndk-NOTICE.txt.

Experimental Mini model source:
https://huggingface.co/uer/roberta-mini-wwm-chinese-cluecorpussmall/tree/5e567169018f5cf84cf1d85a2b391c8d51211eda
ONNX Runtime source: https://github.com/microsoft/onnxruntime/tree/v1.26.0
The INT8 adaptation and reproducible asset preparation are described in
docs/model-integration.md; host conversion code is tools/export-model-benchmark.py.

Kaomoji data retains LGPL-3.0. The original category file is tracked at
ime-ui/src/main/assets/expressions/rime-kaomoji-source.txt, with SHA-256
0772e42f7410b4ed452b1103bcf2d9360ec952318383631d0702bc0418931d62.
The adapted data is ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/KaomojiCatalog.kt.
Both files are included as readable APK assets under expressions/. Modify the
Kotlin data and rebuild with the documented Gradle commands to replace the
compiled catalog. No runtime download, Python generator or Rime schema change
is required. The independent application code retains Apache-2.0.

## Offline glide and emoji

CMUdict source: https://github.com/cmusphinx/cmudict/tree/74790861f652b15e4ac49015a90074ad62a27690
The source and derived hashes, filtering steps and rebuild command are in
docs/glide-decoder-validation.md. Run engine-english/tools/prepare-glide-lexicon.py
with the hash-verified source dictionary to regenerate the bundled spelling list.

Emoji uses Unicode Emoji 18.0, CLDR 48 and Noto Emoji revision
e20cbc2bbec1926686be9f9bee7d1d2cfa1fea0e. Exact source URLs, complete commits,
PNG and derived WebP hashes are in tools/emoji/sources.lock.json. Run
`python tools/prepare-emoji.py` to reproduce assets, then
`python tools/prepare-emoji.py --verify` for an offline exact-set audit.

## Offline handwriting and corresponding source archive

PaddlePaddle OCR source:
https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/tree/ed152b8b495f84de93cda5709d768548a9127622
Preparation and model hashes are in tools/prepare-handwriting-model.py.
Zinnia algorithm source:
https://github.com/taku910/zinnia/tree/581faa8f6f15e4a7b21964be3a5ec36265c80e5b

The Tegaki 0.3 data archives, under LGPL-2.1, are:

- https://github.com/tegaki/tegaki/releases/download/v0.3/tegaki-zinnia-simplified-chinese-light-0.3.zip
  SHA-256: 598787133a4d59fcf3a2fbc5654c68eaf35a8e422274efcea2035cc081f3446c
- https://github.com/tegaki/tegaki/releases/download/v0.3/tegaki-zinnia-traditional-chinese-0.3.zip
  SHA-256: f41032e67a4eff056813d243eabc32ea07eea404c714bdb5882a5b0fcda51690

Each archive includes the original model, corresponding training XML, COPYING
and build files. The test APK packaging command distributes these exact archives
in a separate `handwriting-sources.zip`, with the current adaptation scripts and
this file. Preserve that archive alongside the APK and notices when redistributing.
The format conversion filters valid Han labels and rearranges sparse weights;
it does not retrain or secretly modify the training data.

To reproduce the shipped tables, extract the corresponding-source archive into
a project checkout, preserving its `build/handwriting-stroke-model` and `tools`
paths. With Python, NumPy and Pillow installed, run from the repository root:

```powershell
python tools/prepare-handwriting-stroke-model.py
```

To replace the data with modified tables, edit or retrain the source models using
the included upstream build files, update the explicit source size/hash records
in that script, regenerate the `.zsh` files, and update the model size/hash records
in `model-scoring/build.gradle.kts` and `HandwritingStrokeAssets.kt`. Rebuild the
APK with the repository Gradle wrapper. Integrity checks remain enabled. The
runtime Kotlin adaptation is in `model-scoring/src/main/kotlin/dev/zeroinput/model/`
and is distributed in the corresponding-source archive for this build.
