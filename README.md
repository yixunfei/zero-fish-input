# zero fish input

<img src="logo.jpg" alt="zero fish input" width="112">

ZeroInput 是面向 Android 的开源输入法。中文、英文、词联想、表情和安全剪贴板可离线使用。AI 联网和公开资源下载由用户主动启用。

[下载 v0.7.6 测试版](https://github.com/yixunfei/zero-fish-input/releases/tag/v0.7.6) · [发布说明](docs/releases/v0.7.6.md) · [发布验证](docs/releases/v0.7.6-validation.md)

## 安装

1. 从发行页下载 [通用 APK](https://github.com/yixunfei/zero-fish-input/releases/download/v0.7.6/zero-fish-input-0.7.6-debug-universal.apk)。它支持 Android 8.0（API 26）及以上，包含 `arm64-v8a`、`armeabi-v7a` 和 `x86_64`。
2. 对照同页的 `SHA256SUMS.txt` 核对下载。Windows 可运行 `Get-FileHash .\zero-fish-input-0.7.6-debug-universal.apk -Algorithm SHA256`。
3. 安装后打开 **zero fish input**，点「启用」进入系统输入法设置。启用后返回应用，点「切换」并选择 **zero fish input**。设置页显示「正在使用」后，在普通文本框输入 `nihao`，点选“你好”。

公开包是 Debug 签名测试版，包名为 `dev.zeroinput.ime.debug`。已在模拟器验证从官方 v0.7.1 覆盖安装。遇到签名冲突时请先确认原安装来源，卸载会清除该应用的本地数据。首次启动会在后台准备中文数据，期间可用内置基础词表离线输入。

## 日常输入

- **中文**支持全拼、简拼、模糊音、简繁切换、九键，以及全键盘下的微软双拼和自然码。输入 `nmen` 可选“你们”，`nmeng` 的首屏可选“柠檬”和“你们”。空格提交高亮候选，回车提交原始拼音。
- **候选与组合**支持滑动继续浏览、展开候选、移动拼音光标和逐段选字。上屏后可从工具栏重选上一词，具体取决于宿主编辑器支持。
- **切换语言**会保留未完成的输入；编辑器拒绝提交时保留当前组合。词库编辑尚未核验或全部候选被过滤时，空格提交原始拼音并补空格；候选查询等待期间保留已核验的候选页。
- **数字与符号**可直接切换。符号首屏放置 27 个常用符号，点 `‹`、`›` 或左右滑动翻页，点分组名直接跳转。点左括号默认补齐一对并把光标放在中间。可在「设置 → 输入 → 成对符号自动补全」关闭。
- **英语与个性化**支持离线英文候选、滑行输入、词联想、加密用户词组和词频管理。词库管理可查看来源、编辑词条和控制公开词库启用状态。
- **词频学习**：普通输入中点选候选会记录使用次数，达到提升阈值后参与排序，默认阈值为 1。个人优先级会保留在模型排序和词库编辑混排中；空格、回车的自动学习需单独开启。应用仅关闭预测或自行补全时，普通聊天／搜索框仍可学习显式选择的中文候选，此类输入不学习空格等隐式提交。密码、未知、邮箱／网址、明确禁止个性化、隐身、关闭学习以及私密输入、AI 和键盘内搜索草稿不学习。
- **表情与手写**提供 Emoji 搜索、最近使用、收藏、自定义颜文字和可按需下载的离线手写模型。退格支持整段删除省略显示选择符的组合 Emoji；私密输入、AI 草稿和表情搜索中的已确认文字也按完整字符退格。键盘外观可设置主题、尺寸和候选布局。
- **键盘工具栏**一级提供近似字输入、私密临时输入和安全剪贴板。「更多」将复制选区放在首位，安全剪贴板面板也可直接复制。
- **键盘内复制**：在支持选区读取的普通输入框选中文字后，从「更多」打开复制确认。完成系统身份认证后，在原输入框的键盘内点击「保存选中文字」。来源应用的选字菜单由其自身与系统控制；支持的应用也可用「复制到 ZeroInput」或文本分享入口。
- **设置导航与外观**：首页按输入、键盘外观、词库与语言、隐私与剪贴板、AI、通用与关于分类，分类标题和说明整行可点。返回分类或旋转后保留浏览位置。外观页支持实时预览、按键与候选样式，以及 RGB 自定义背景色。薄荷绿和樱花粉均有深浅配色；预设保留高度、候选和用户背景设置。
- **草稿编辑**私密输入、键盘 AI 草稿和表情搜索可点击正文定位光标，在中间插入文字或退格；成对括号会将光标放在括号中间。有未完成拼音时，点击会先完成当前转换。私密输入中被遮住的部分需先点击「本次明文」才能定位。
- **同型字查找**可在「更多工具」中选部首或输入拼音选近似字，连续滑动浏览按部件和结构排序的带拼音候选，点选后插入。

## 隐写模式

在普通文本框的键盘工具栏点击象形后面的「隐」，在同一可滚动页面填写公开文本、秘密正文和密钥／口令，点击对应输入区域即可使用下方键盘编辑。
六种算法直接展开为复选框：AES、XChaCha20、Kuznyechik、Twofish、Camellia、SM4。按勾选先后组合加密，编号显示顺序；可直接调整支持的密钥位数或提前某一层。至少选择一种。
「更多选项」支持口令、32 字节 Base64url 随机共享密钥、密钥文件及两种组合凭据；选择文件会先清除秘密草稿，请在编辑正文前选择。
点击「加密并填入」，自检成功后自动将最终隐写文本填入原输入框，不触发发送。需要先保存时点击「仅生成」，结果页仍可手动提交。凭据不保存，丢失后无法恢复。
结果页可分别一键进入秘密明文、公开掩护文本、独立密文和完整隐写结果的私密保存流程；编辑时也可保存当前非凭据字段。
「存入私密空间」使用安全剪贴板私有加密库，仍需系统身份认证和保存确认，不写入系统剪贴板。进入保存流程会结束当前隐写操作。
独立密文为 `[ZIS1:…]`，不包含公开文本；导入后须填写加密时完全相同的公开文本才能解密。完整隐写结果已包含公开文本。

解密可在选中完整载体后导入选区，或通过选字／分享入口选择「本地解密隐写文本」，也可认证后读取一个安全剪贴板条目。
临时私密输入默认遮盖全部已输入文字，不再露出末尾字符；主动点击显示全文才会展开。
解密结果默认隐藏并只在本机显示。插入明文需单独确认，保存正文需系统认证后再次确认。
解密凭据方式由载体决定。保存草稿最多保留 120 秒，认证成功后须在 30 秒内确认；取消认证可在草稿有效期内重试。密钥文件读取最多等待 120 秒，超时会明确提示。
公开文本和待解密文本长按可选择从系统剪贴板或安全剪贴板粘贴；系统来源仅主动读取一个有界纯文本条目。安全来源仍需认证和确认，原草稿清除，返回原输入框点击“隐”继续。随机主密钥支持 256、512、1024、4096 位及 256–4096 间 8 的倍数自定义长度；算法子密钥仍按各算法支持的位数派生，不代表等量安全强度。不直接提取页面。退出模式、切换会话、修改设置、锁屏或超时会清除临时内容。

Unicode 隐写与可见 Base64url 均可被检测，也可能被目标应用改写或移除。自然措辞载体因尚无通过容量、语义及检测门槛的资源而不可用，不会自动切换其他载体。
秘密正文上限 2048 UTF-8 字节，公开文本上限 512 UTF-16 单位／1536 UTF-8 字节，单个密钥文件 32 字节至 1 MiB。
使用与验证范围见[隐写验收记录](docs/text-steganography-validation.md)。此处描述当前源码功能；上方已发布的 v0.7.6 链接不代表包含这些未发布改动。

## 隐私与可选联网

- 密码、PIN 等敏感编辑器按隐私策略限制学习和个性化。用户数据在本机保存，应用关闭系统备份。主动清除或卸载前请确认需要保留的数据。
- 安全剪贴板是默认关闭的私有加密库。主动添加或导入内容需要确认，每次读取正文需要认证。熄屏会取消正在处理的私密粘贴。独立的系统剪贴板防护默认关闭，只处理元信息，可按设置提醒或清理当前项。
- **私密临时输入**可在「隐私」或键盘一级工具栏中开启，开关时需认证。开启后，符合条件的普通文本框默认进入私密副框。副框显示当前拼音候选和最近三个字，可用「本次明文」显示整段。「保存并关闭」把草稿加密存入安全剪贴板。「保存并提交」插入当前输入框，默认也保留加密副本，可在设置中关闭。「仅提交」只写入当前输入框。私密粘贴仍需单独认证。
- 词库管理中的公开资源下载需要主动操作，已安装的词库和模型可以离线使用。完整包内置万象核心词表和 Mini INT8。轻量包保留核心词表，Mini INT8、LTS 语法和手写资源可按需下载。
- QQ、搜狗的首页和分类页直接列出可下载词库。点击「分类、排序与来源分页」浏览其他结果，再点选词库确认下载并导入。
- 在「内置与下载词库」中选择来源和词库，确认「下载并导入」后，应用会校验并安装受支持的 Rime YAML、QQ `.qcel` 或搜狗 `.scel` 文件。「访问来源网页」是单独的浏览器入口。取消任务后会返回已安装列表，已完成的导入仍保留。
- AI 工作台可从桌面独立入口或键盘打开。AI 总开关、联网和会话保存默认关闭。启用联网后，可向配置的 HTTPS 模型提交草稿和选定内容。角色、显式记忆、Skill、文件工具、远程工具和设备代理各有独立的启用或审批边界。
  设备代理的动作类别（含读屏）在尚未配置时均未授权，需在设置中主动勾选；已明确保存的选择会保留。

详见 [安全说明](SECURITY.md)、[威胁模型](docs/threat-model.md) 和 [架构说明](docs/architecture.md)。

## 从源码构建

以下命令从仓库根目录运行，使用 Windows PowerShell。需要 Git、`tar`、Python 3.11+、JDK 17、Android SDK Platform 36、Build Tools 36.0.0、NDK `28.2.13676358` 和 CMake。设置 `JAVA_HOME` 和 `ANDROID_SDK_ROOT`，或在本机 `local.properties` 中配置 `sdk.dir`。Gradle 由仓库 Wrapper 提供。

Rime 与公开词表使用固定版本和校验值。

```powershell
./tools/bootstrap-rime.ps1
python tools/dictionaries/prepare.py
python tools/dictionaries/verify.py
```

构建需要 Python 依赖、模型和本地 AAR。按各组件的说明配置环境后，运行模型准备命令。[Mini INT8](docs/model-integration.md#reproduce-assets-and-build) · [手写资源](docs/adr/0016-offline-chinese-handwriting.md) · [精简 ONNX Runtime](tools/onnxruntime/README.md)

```powershell
python -B tools/evaluate-small-model.py --model mini --download
python -B tools/export-model-benchmark.py --model mini --int8
python -B tools/prepare-handwriting-model.py --include-quality-fixtures
python -B tools/prepare-handwriting-stroke-model.py
```

准备完毕后，构建 x86_64 Debug APK 并运行验证。

```powershell
./gradlew.bat :app:assembleDebug testDebugUnitTest privacyCheck :app:lintDebug `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel
```

通用测试包包含许可证、手写模型对应源码和 SHA-256 清单。通用包实测大小见[发布验证](docs/releases/v0.7.6-validation.md)。80 MB 包体目标针对 ARM64 完整包单独验收。

```powershell
./tools/package-test-apk.ps1 -Abi universal -MaxApkBytes 180000000
```

测试包输出至 `app/build/outputs/test-apk/`。设备验收用法见 [贡献指南](CONTRIBUTING.md)。如需自行构建轻量版，参阅 [公开资源与 full/lite 包](tools/resources/README.md)。本地 Release 构建需要 `-PrequireRime=true`，默认保持未签名。

## 项目资料

- [架构与模块职责](docs/architecture.md) · [架构决策](docs/adr/) · [当前进度](docs/project-progress.md)
- [贡献指南](CONTRIBUTING.md) · [安全问题报告](SECURITY.md) · [历史版本](docs/releases/)
- 项目代码采用 [Apache-2.0](LICENSE)。第三方数据与模型按各自许可分发，见 [第三方声明](THIRD_PARTY.md)、[来源](SOURCES.md) 和 [NOTICE](NOTICE)。

### Audit fixes (2026-11-18)

AI settings discard unsaved provider drafts when the settings window stops;
reopen the provider editor to edit them again. Failed saves reload persisted providers with AI and network switches off before allowing another edit; unchanged configuration reloads preserve queued requests. Text language-pack dictionaries
must keep each line within 4096 characters and 16 fields. JSON dictionaries load
incrementally, with at most 16 container levels, 4096 characters per token,
50,000 keys per object and 100,000 simultaneously retained object keys. They keep
the existing 16 MiB per-file, 32 MiB total and 50,000 output-entry limits; data
after the output limit is still validated. Test APK packaging now
checks Rime, the C++ runtime and both ONNX libraries for every requested ABI.
