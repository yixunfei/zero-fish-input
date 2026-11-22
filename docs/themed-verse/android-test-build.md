# Android 自然措辞隐写（Debug）

## 当前隐写工作区

2026-11-22，五言集句、七言集句及窗边随笔的 Debug 试验阶段结束。发端在「隐 → 载体 → 自然措辞」选择样式，填写秘密正文和凭据，点「生成并预览」查看全文、原始正文和完整载体的 UTF-8 字节数及利用率，核对后可确认插入。收端在「隐 → 解密」读入完整文字，保留原字、标点及换行。点「开始解密」选择发件人的样式，按提示提供凭据后解密。也可在载体菜单手动选样式。

窗边随笔最多承载 833 B 完整封装，实际可装正文随压缩及加密配置变化。旧窗边和日常短句载体需用对应版本读取。固定公开样例仅供仪器测试直接启动，设置页已移除入口。Release 仍须单独验收。[当前协议和开放条件](../adr/0041-natural-wording-trial.md) · [短消息 Android 实测](../project-progress.md#eight-han-passphrase-messages-in-android-prose-2026-11-22)

### 当前回归

从仓库根目录构建 x86_64 Debug 应用和测试包。选定专用测试模拟器，将 `$serial` 设为其 `adb devices -l` 序列号。三条键盘用例分别覆盖生成、预览、显式插入、错误样式和独立解密。

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest privacyCheck :app:lintDebug :app:mergeReleaseAssets :app:processReleaseMainManifest :app:compileReleaseKotlin --offline --no-parallel -PrequireRime=true "-Pandroid.injected.build.abi=x86_64" "-Pandroid.injected.testOnly=false"
adb -s $serial install -r -t app/build/intermediates/apk/debug/app-x86_64-debug.apk
adb -s $serial install -r -t app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk
$cases = @(
    'StegoKeyboardIntegrationTest#fiveCharacterVerseOpensTheStylePickerDirectlyWhenDecryptingUnmarkedText'
    'StegoKeyboardIntegrationTest#wordingFromTheImeUsesSevenCharacterVerseWithoutALegacyReceiveOption'
    'StegoKeyboardIntegrationTest#wordingFromTheImeUsesTheRefinedWindowObservationProfile'
    'ShortProsePlatformTest'
    'VerseTestPlatformTest#settingsOmitsPublicFixtureAndFixtureRemainsPrivate'
    'VerseTestPlatformTest#fullPageOpensHostAndLocalSamplesAndClearsOnRecreation'
)
$classes = ($cases | ForEach-Object { "dev.zeroinput.ime.textstego.$_" }) -join ','
adb -s $serial shell am instrument -w -r -e class $classes dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## 首版工作区记录

2026-11-21。Debug 测试包在现有键盘工作区开放「隐 → 载体 → 自然措辞 · 五言集句」。

1. 发端填写秘密正文，生成或输入 256 位随机共享密钥，点击「生成并预览」。预览显示完整的可滚动集句、原文和成品 UTF-8 字节数、利用率及 20% 判定。明确点选插入后才将集句填入当前输入框。低于 20% 的成品可在试用包内确认插入。
2. 收端打开「隐 → 解密」，选择同一载体，导入编辑器选中的完整集句，或通过已启用的长按来源读入全文。保留原字、标点及换行。输入同一串 43 字符随机密钥，点击解密。认证后的正文默认遮盖。

发端和收端复用隔离草稿、当前编辑器校验与保存确认。集句固定使用单层 AES-256-GCM，并以独立认证绑定锁定冻结资源和规范全文。Release 开放需要单独评审。[试用协议评审草案](../adr/0041-natural-wording-trial.md)

### 生成按钮反馈修复

2026-11-21。真实触屏点击复现了密钥为空时提示被表单裁出屏幕的问题。生成密钥与预览按钮现固定在操作栏，校验提示在按钮上方显示，缺少正文或密钥时定位对应字段。生成成功后收起输入键盘，在同一个受保护工作区展示可滚动的全文。插入仍需点击确认。

API 37 x86_64 模拟器用真实触摸事件验证空正文、空密钥、无效密钥及成功收发。默认布局、简体中文系统界面以及 480×854、200 dpi、1.3 倍字体的深色竖屏和横屏均通过。回归检查提示的实际可见区域、预览首行、明确插入和接收方回读。隐写平台回归实际执行 26 项并全部通过，上一轮独立主机向量的 1,856 B 正文在修复版再次完整恢复。

修复包为 97,123,139 B。全量单测、`privacyCheck`、Lint、真实 Rime 构建、签名、ABI 和权限检查均通过。ARM64 真机待验。[ARM64 Debug 修复包](../../app/build/outputs/test-apk/20261121-101521/zero-fish-input-0.7.7-debug-arm64-v8a.apk) · [SHA-256 校验清单](../../app/build/outputs/test-apk/20261121-101521/SHA256SUMS.txt)

### 工作区验证

- API 37 x86_64 模拟器使用真实 IME 和编辑器完成发端生成密钥、遮盖草稿、预览、确认插入、收端显式选择、读取选区和认证后揭示。改变外部选区撤销当前工作区。单测覆盖错密钥、错误认证域、修改字句或布局、替换配置、容量超限以及私密库返回代次。
- 常规平台回归实际执行 22 项，全部通过。另有 2 项公开跨端夹具单独运行通过，3 项系统 PIN 或指纹用例按夹具条件跳过。真实 IME 往返在窄屏深色竖屏、横屏设置下分别复跑通过。Release 编译产物及合并清单也验证了试用后端的变体边界。
- 独立 Python/Rust 主机与 Android 用试用认证域双向互通。固定公开向量包含扩展区汉字、emoji、组合字符及 CRLF，1,856 B 原文在两个方向逐字节恢复。两份成品各为 3,868 UTF-8 B，封装均为 418 B。
- 同一批 20 篇研究摘要用 Kotlin 封装和集句重跑。20/20 逐字节恢复，11/20 达到 20%。其余 9 篇均成功生成，完整载体超过各自 `5 × 原文字节数` 上限 125–914 B。60 篇留出样本仍封存，整篇自然度待独立盲评。[校准数据口径](p2-theme-calibration.md)

冷、热耗时使用相同 1,856 B 公开向量，在 API 37 x86_64 模拟器分别从新进程开始。每方向测 1 次冷调用和 10 次热调用，计入从正文到载文或从载文到认证正文的完整编解码。冷调用也计入加载固定资产，计时止于编解码返回。

| 操作 | 冷 | 热 P50 | 热 P95 |
| --- | ---: | ---: | ---: |
| 生成并自检 | 167.5 ms | 22.1 ms | 28.5 ms |
| 解析并认证回读 | 130.8 ms | 12.9 ms | 17.6 ms |

### 工作区试用 ARM64 测试包

2026-11-21 打包。全量单测、`privacyCheck`、Debug Lint、`requireRime=true` 构建及 APK 签名、ABI 和权限核对通过。产物为 97,119,343 B，满足 180 MB 测试包上限，高于 80 MB 完整包目标。ARM64 真机功能待验。

SHA-256 为 `e8a670edaf7b838101d96b7bf1a9e0c2e5cd02e2fb6f081d9188ee656976d645`。
[ARM64 Debug APK](../../app/build/outputs/test-apk/20261121-000415/zero-fish-input-0.7.7-debug-arm64-v8a.apk) · [校验清单](../../app/build/outputs/test-apk/20261121-000415/SHA256SUMS.txt)

### 独立主机互通复现

使用下方构建命令安装本次 x86_64 Debug 与 AndroidTest 包后，在隔离模拟器复现双向互通。以下只处理包内固定公开夹具。

```powershell
$verseAdb = 'D:/env/AndroidSDK/platform-tools/adb.exe'
$verseDevice = 'emulator-5590'
$verseTest = 'dev.zeroinput.ime.debug.test/androidx.test.runner.AndroidJUnitRunner'
$verseTestClass = 'dev.zeroinput.ime.textstego.VerseTestPlatformTest'
$verseAssets = 'app/build/generated/verifiedWordingAssets/themed-verse'
$verseScratch = (New-Item -ItemType Directory (Join-Path '.tmp-evidence' ('wording-interop-' + (Get-Date -Format yyyyMMdd-HHmmss)))).FullName
cargo build --offline --locked --manifest-path tools/stego-vectors/Cargo.toml --bin verse-envelope
python tools/themed-verse/check_android_wording.py send $verseAssets "$verseScratch/host.txt"
& $verseAdb -s $verseDevice push "$verseScratch/host.txt" /data/local/tmp/zeroinput-wording-host-public.txt
& $verseAdb -s $verseDevice shell "cat /data/local/tmp/zeroinput-wording-host-public.txt | run-as dev.zeroinput.ime.debug sh -c 'cat > cache/wording-host-public.txt'"
& $verseAdb -s $verseDevice shell am force-stop dev.zeroinput.ime.debug
& $verseAdb -s $verseDevice shell am instrument -w -e wordingInteropEvidence true -e class "$verseTestClass#wordingTrialOpensAnIndependentPublicHostCarrier" $verseTest
& $verseAdb -s $verseDevice shell am force-stop dev.zeroinput.ime.debug
& $verseAdb -s $verseDevice shell am instrument -w -e wordingInteropEvidence true -e class "$verseTestClass#wordingTrialProducesAPublicCarrierForTheIndependentHost" $verseTest
```

使用 PowerShell 7.4+ 的二进制重定向将公开 Android 夹具取回，再用独立主机回读。

```powershell
& $verseAdb -s $verseDevice exec-out run-as dev.zeroinput.ime.debug cat cache/wording-android-public.txt > "$verseScratch/android.txt"
python tools/themed-verse/check_android_wording.py verify $verseAssets "$verseScratch/android.txt"
```

## 历史设置页公开样例（入口已移除）

2026-11-20。在 ARM64 Debug 测试包内打开「设置 → 隐写 → 集句隐写试验」。点击「生成公开样例」查看完整五字集句、原文与成品字节数及 20% 判定，点击「回读当前成品」验证本机生成结果。「打开主机样例」使用冻结的跨端向量。三个动作均使用包内公开样例和公开测试密钥。

该页为不导出的 Debug Activity。样例、密钥和结果均在本地处理，锁屏、离开页面或重建时清除预览并撤销待公布的结果。公开测试密钥仅适合公开样例。研究摘要的留出集和整篇自然度待验。[容量校准](p2-theme-calibration.md) · [试读验收卡](p2-plan.md#研究摘要校准盲评任务卡)

## 实现

- `ime-core/src/debug` 用纯 Kotlin 解析三组句池、混合进制和规范版式，并受整条 UTF-8／UTF-16 上限约束。GB18030 字节映射冻结自 Python 参考配置，字典与映射均按长度和 SHA-256 校验。解压使用原长四倍的上界，检查结束、尾随数据及 UTF-8 原长。
- `security/src/debug` 复用既有消息封装的 AES-256 正文层和 XChaCha20-Poly1305 元数据认证。它固定 P2 的测试配置摘要、32 字节公开测试密钥模式、单层 AES-256 和 64 B 档位。通用文本入口继续校验其 UTF-8 字符输入与输出。
- `app/src/debug` 提供受保护的独立本地测试页，以及按冻结源生成的测试资产。资源和编解码在有界后台线程执行，结果保留在当前前台页。[源资产署名](../../tools/themed-verse/android-test-profile/ATTRIBUTION.md)

## 复现

从仓库根目录运行。Debug 构建从已锁定的万象五字表复建资产，字典和互通夹具保存在源文件中。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'dev.zeroinput.ime.textstego.*' :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-parallel -PrequireRime=true "-Pandroid.injected.build.abi=x86_64" "-Pandroid.injected.testOnly=false"
.\tools\package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000
```

在隔离的 x86_64 模拟器上安装上述 Debug 和 AndroidTest 两份中间 APK，运行 `dev.zeroinput.ime.textstego.VerseTestPlatformTest`。设 `-e verseInteropEvidence true` 可让测试将**公开**成品写入调试缓存，供 Python/Rust 主机回读。普通测试页的成品只在内存展示。

冻结试验配置摘要为 `a1790ee980dc97d76d1d5d90c37dfdfc0b50718bcc2a1f857dbb34e6cf66ff1a`。主机互通向量正文为 1,856 B，包含繁体、扩展区汉字、emoji、组合字符和 CRLF，封装 418 B。主机载体为 3,868 UTF-8 B 和 1,324 UTF-16 单元。[主机向量生成所用配置](p2-theme-calibration.md#复现)

## 验证结果

- 两次资产构建逐字节一致。合并清单与 Release 类输出验证了试验组件的 Debug 边界。
- x86_64 API 37 模拟器通过三个平台用例，覆盖设置入口、保护窗口、本机生成及回读、打开主机向量和重建清理。小屏深色条件下另从竖、横两种旋转设置各复跑一次。模拟器生成的 3,868 B 成品由独立 Python/Rust 主机恢复全部 1,856 B。
- JVM 侧运行相同编解码类，复核 20 篇研究摘要，20/20 逐字节恢复、11/20 达到 20%。zlib 输出长度允许跨实现变化，其中 `exam-42616` 回归由 994 B 升至 1,058 B 封装档，仍达到 20%。该统计与模拟器公开互通样例分别计量。
- Debug 单测覆盖非法和截断集句、损坏密文、错密钥、错配置、受认证的错误算法组合、越界解压、尾随数据和改动的资源。既有精确文本入口参与常规回归。

### 公开样例 ARM64 测试包

完整单测、`privacyCheck`、Debug Lint、原生 Rime 构建、APK 签名、ABI、权限和资产核对均通过。APK 为 97,101,787 B，满足 180 MB 测试包上限，高于 80 MB 完整包目标。六份试验资产的 APK 压缩字节合计为 622,482 B。设备功能验证在 x86_64 模拟器完成，ARM64 真机待验。

SHA-256 为 `e02bec828d778295c32146eec32de9698b7537a43a101ade9873289c3dddade2`。
[ARM64 Debug APK](../../app/build/outputs/test-apk/20261120-202507/zero-fish-input-0.7.7-debug-arm64-v8a.apk) · [校验清单](../../app/build/outputs/test-apk/20261120-202507/SHA256SUMS.txt)
