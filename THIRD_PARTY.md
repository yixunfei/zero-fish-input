# Third-party notices

ZeroInput 自有源码使用 Apache-2.0。下列运行时组件和数据保持各自许可证；Rime 依赖由
`tools/bootstrap-rime.ps1` 固定并校验，模型资产由对应准备脚本及构建任务校验。
完整许可原文位于 [`LICENSES`](LICENSES)。

| Component | Pinned version | License | License text | Purpose |
| --- | --- | --- | --- | --- |
| librime | 1.13.1 (`1c233581`) | BSD-3-Clause | `librime-BSD-3-Clause.txt` | 中文输入引擎 |
| darts-clone | librime bundled copy | BSD-3-Clause | `darts-clone-BSD-3-Clause.txt` | librime 数据结构 |
| rime-luna-pinyin | `46acf031` | LGPL-3.0 | `rime-data-LGPL-3.0.txt`, `GPL-3.0.txt` | 全拼词典 |
| rime-essay | `0766c929` | LGPL-3.0 | `rime-data-LGPL-3.0.txt`, `GPL-3.0.txt` | 词频数据 |
| Boost | 1.89.0 | BSL-1.0 | `Boost-1.0.txt` | librime 构建依赖 |
| OpenCC | `e5d6c5f1` | Apache-2.0 | `Apache-2.0.txt` | 离线简繁转换及 TS/ST 字符、词组数据 |
| LevelDB | `99b3c03b` | BSD-3-Clause | `leveldb-BSD-3-Clause.txt` | librime 用户数据 |
| yaml-cpp | `f7320141` | MIT | `yaml-cpp-MIT.txt` | 配置解析 |
| marisa-trie | `0d4e8ab5` | BSD-2-Clause（本项目采用双许可证中的 BSD 选项） | `marisa-trie-COPYING.md` | 词典数据结构 |
| AndroidX libraries | 见 `gradle/libs.versions.toml` | Apache-2.0 | `Apache-2.0.txt` | Android 运行时支持 |
| AndroidX Test runner / JUnit extension | 1.6.2 / 1.2.1 | Apache-2.0 | `Apache-2.0.txt` | 仅模拟器/设备测试 APK；使用已有缓存，不进入应用运行时，无联网或采集路径 |
| Material Components | 1.12.0 | Apache-2.0 | `Apache-2.0.txt` | Android UI |
| Kotlin runtime | 2.1.21 | Apache-2.0 | `Apache-2.0.txt` | Kotlin 运行时 |
| PaddlePaddle PP-OCRv5 mobile recognition model | `ed152b8b495f84de93cda5709d768548a9127622` | Apache-2.0 (model card) | `Apache-2.0.txt` | 离线中文单字手写识别模型与派生字符表 |
| CMUdict spellings | `74790861f652b15e4ac49015a90074ad62a27690` | BSD-style | `cmudict-LICENSE.txt` | Offline English glide dictionary |
| Unicode Emoji / CLDR annotations | Emoji 18.0 / CLDR 48 (`acd6d88a`) | Unicode License V3 | `Unicode-V3.txt` | Complete RGI sequences and localized search metadata |
| Noto Emoji artwork | `e20cbc2bbec1926686be9f9bee7d1d2cfa1fea0e` | Apache-2.0 artwork; upstream flag terms | `noto-emoji-artwork-LICENSE.txt`, `noto-flags-LICENSE.txt`, `noto-emoji-OFL-1.1.txt` | Offline WebP images; no font binary bundled |
| Zinnia feature/scoring algorithm | `581faa8f6f15e4a7b21964be3a5ec36265c80e5b` | BSD-3-Clause | `Zinnia-BSD-3-Clause.txt` | Kotlin stroke recognizer adaptation |
| Tegaki Chinese stroke model data | 0.3 simplified-light / traditional | LGPL-2.1 | `Tegaki-LGPL-2.1.txt` | Offline simplified/traditional handwriting tables |
| rimeinn/rime-kaomoji expression data | `e66d8f26ee47b4a2a840983b7d71ed69b5ecf756` | LGPL-3.0 | `rime-kaomoji-LGPL-3.0.txt`, `GPL-3.0.txt` | 离线颜文字分类数据，原始与适配后的数据源码随 APK 分发 |
| Android NDK runtime | 28.2.13676358 | 多许可证 | `android-ndk-NOTICE.txt` | `libc++_shared.so` 与 native 运行时 |

Rime 与颜文字的 LGPL 源数据随 APK 提供；Tegaki 笔画表的原始训练 XML、模型、构建文件和
适配脚本另随对应源码归档提供。ZeroInput 的独立源码仍采用 Apache-2.0。分发二进制时必须同时
提供本目录、`NOTICE`、`SOURCES.md` 及对应源码归档。

OpenCC 的 `TSCharacters.txt`、`TSPhrases.txt`、`STCharacters.txt` 与 `STPhrases.txt`
从已固定且校验的 OpenCC 源码生成 APK 资产；每个文件的 SHA-256 同时固定在
`engine-rime/build.gradle.kts`。这些数据沿用 Apache-2.0，无新增运行时依赖、权限或联网路径。

九键的 `pinyin-syllables.txt` 在构建时由同一固定版本的 Luna Pinyin 词典提取，沿用该数据的
LGPL-3.0 许可；原始词典继续随 APK 提供。`engine-dictionary` 的算法和有限参考词表为 ZeroInput
自有 Apache-2.0 源码/数据，没有引入另一个第三方运行时或词典。

颜文字数据参考并改编自固定版本 `rimeinn/rime-kaomoji/opencc/kaomoji_category.txt`，保留文本中的
空格并调整分组，增加问候与晚安项；`KaomojiCatalog.kt` 数据文件采用 LGPL-3.0。原始文件 SHA-256
为 `0772e42f7410b4ed452b1103bcf2d9360ec952318383631d0702bc0418931d62`，构建时校验。
APK 的 `assets/expressions/` 包含原始数据与可修改重建的 Kotlin 数据源码；界面、搜索、加密存储
代码仍采用项目 Apache-2.0 许可。没有新增运行时库、权限或下载路径。
`aoguai/rime_kaomoji_dict` 和 `overmind1980/-` 仅作参考，未将其收集数据打包分发。

## Offline Chinese handwriting

Offline handwriting bundles PaddlePaddle's `PP-OCRv5_mobile_rec_onnx` graph at
revision `ed152b8b495f84de93cda5709d768548a9127622`. The fixed model card
declares Apache-2.0; see `LICENSES/Apache-2.0.txt`. The ONNX graph is 16,534,782
bytes (SHA-256 `da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092`).
Its derived character table is 74,012 bytes (SHA-256
`d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b`).
The pinned source YAML is SHA-256
`5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e`.
ONNX Runtime is already included for the short-word scorer;
the app has no runtime download. The upstream line-recognition benchmark does
not establish accuracy for this single-character touch UI.

The hybrid recognizer also adapts Zinnia's feature/scoring algorithm in Kotlin
(copyright 2005-2007 Taku Kudo, BSD-3-Clause) and repacks Tegaki 0.3 Chinese model
data under LGPL-2.1. Simplified and traditional runtime tables total 46,068,784
bytes. `tools/prepare-handwriting-stroke-model.py` records source archive and model
hashes, filters valid Han labels, and converts sparse weights to a bounded
feature-major table. This adds no native library or runtime network path.
The exact source archives include training XML, original models, COPYING and
build files; `tools/package-test-apk.ps1` distributes them together with the
adaptation scripts. Rebuild/replacement instructions and exact hashes are in
`SOURCES.md` and `docs/handwriting-quality-validation.md`. The larger public data
improves the measured stroke fixtures at a package/memory cost; the small test
corpus is not a general accuracy claim.

## Glide dictionary and complete emoji

The English glide lexicon derives 124,082 unique ASCII spellings from pinned
CMUdict, dropping pronunciation columns and variant markers. The source SHA-256
is `81917843c7f44ce2b094ac63873c2c7a4cf802040792c455ba3ca406891c3d22`;
the derived 1,050,045-byte text resource is
`44b7f9daf8d99d18eeca42523c3922a140562a6e7ef0a695530942ddf8818948`.
The runtime checks its hash. Chinese glide reuses the existing LGPL Rime data;
no additional runtime SDK is introduced.

Emoji uses the exact 3,963 fully-qualified RGI sequences plus 9 components in
the pinned Unicode Emoji 18.0 fixture. Noto images are converted to 3,972 lossless
128px WebP files totaling 15,533,352 bytes. Artwork remains Apache-2.0; flag
attribution and the upstream OFL text are preserved, although no font is bundled.
CLDR 48 supplies public names/keywords; local labels cover newer missing entries.
`tools/emoji/sources.lock.json` pins all commits, source and generated hashes.
`tools/prepare-emoji.py --verify` and the Gradle asset audit require exact coverage.
Public image/data repositories are maintained upstream, but updates are explicit
and hash-reviewed. These resources increase package size; they add no permission,
runtime dependency, network download or personal-data access. See
`docs/rgi-emoji-validation.md` and `docs/glide-decoder-validation.md`.

## Experimental model scoring and evaluation

The `model-scoring` module and separate `tools/model-benchmark` project use
`com.microsoft.onnxruntime:onnxruntime-android:1.26.0` under MIT
([license](LICENSES/onnxruntime-MIT.txt)). Its runtime is initialized explicitly
on a bounded worker and adds no network permission, telemetry configuration or
exported application component. The IME uses the runtime through an engine-api
scoring port. The standalone benchmark itself is excluded from the IME APK.
The runtime is necessary for the evaluated ONNX graph; its AAR is pinned to
1.26.0 and SHA-256 `09c0780ae8d734ef2774bdf498b624729a855e6f9a8e488a0e7398a4e7396032`.
It is maintained upstream, but its full CPU native libraries add 19.58--33.30 MB
per ABI before APK compression. Model/runtime size and limitations are reported
separately; reduced runtimes require a fresh numerical and ABI evaluation.
The runtime's upstream third-party notices from tag `v1.26.0` are preserved in
`LICENSES/onnxruntime-ThirdPartyNotices.txt` (Git blob
`fbd9f9a95f6013d8ecaef81e02b0033e5882a675`) and bundled with both applications.

Host export uses ONNX 1.19.0 (Apache-2.0), ml_dtypes 0.5.3 (Apache-2.0), the
existing host PyTorch 2.12.0+cpu and ONNX Runtime 1.26.0. These host packages are
not bundled. Public UER Tiny/Mini checkpoints are downloaded for local conversion
at pinned revisions with content hashes. The user confirmed the UER
project's Apache-2.0 license as the model licensing basis on 2026-09-08; the
separate model cards do not declare license metadata. The IME bundles only the
Mini INT8 derivative and vocabulary (15,008,304 bytes combined); generated graphs
are staged from ignored build directories with strict size and SHA-256 checks.
Source revision, conversion changes, hashes and the accepted experimental quality
exception are in [model evaluation](docs/small-model-evaluation.md),
[model integration](docs/model-integration.md), and NOTICE.
