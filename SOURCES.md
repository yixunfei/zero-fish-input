# Source and license distribution

Project source: https://github.com/yixunfei/zero-fish-input

Initial release source: https://github.com/yixunfei/zero-fish-input/tree/v0.1.0

Current release source: https://github.com/yixunfei/zero-fish-input/tree/v0.2.0

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
