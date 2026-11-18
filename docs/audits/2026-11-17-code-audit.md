# ZeroInput 代码审计报告（复核版）

| 项目 | 内容 |
| --- | --- |
| 对象 / 基线 | `K:\zeroInput`，`main` @ `26a6cec` |
| 原审计 / 复核日期 | 2026-11-17 / 2026-11-18 |
| 本轮工作 | 逐项核对原报告的实现、调用方、测试、ADR、平台契约和本地存档产物 |
| 修改范围 | 仅更新本报告，未修复产品源码、配置或其他项目文档 |
| 保留标准 | 有可定位证据的实现问题、明确的验证体系缺口和文档错误；剔除误报、既定设计、重复项及未证实推测 |

保留原编号以便追溯，编号前缀不再代表当前严重度。原报告的全量覆盖、测试总数和推测性因果不作为本轮验证结果。

## 1. 保留问题

| 编号 | 当前严重度 | 已核实的问题 | 证据 |
| --- | --- | --- | --- |
| H-5 | High | JNI 缺少完整的 C++ 异常边界 | 源码确认 |
| H-7 | High | 存档 0.7.4 universal 包缺 ARM Rime 库，校验没有逐库检查 | APK ZIP 条目、SHA-256 与校验器源码 |
| M-6 | Medium | 含 API key 的完整 AI 配置长期缓存，撤销使用权不清除仓库缓存 | 启动、更新、撤销调用链 |
| M-18 | Medium | 网络门禁漏检连接及 DNS API | 当前正则匹配实测 |
| L-7 | Medium | 语言包单行字段不受限，可在文件上限内产生内存放大 | 实际解析器的独立 JVM 复现 |
| L-27 | Medium | JNI 分配失败后继续使用结果或执行不允许的后续调用 | 源码确认 |
| M-14 | Low | 缺少覆盖不同引擎实现的一致契约测试集 | 测试源码核对 |
| M-23 | Low | 第三方清单遗漏 Release 依赖图中的 kotlinx-coroutines | Gradle 解析与声明文件 |
| L-14 | Low | Android 8.x 自身空项去重可能误标外部替换项 | 源码可确定的交错 |
| L-42 | Low | 语言键长按提示固定为中文 | 构造参数与长按闭包 |
| D-1 | Low | 架构依赖图遗漏两条实际依赖 | 模块构建文件与架构图 |
| D-2 | Low | 认证导航时限文档为 60 秒，实现为 30 秒 | ADR 与实际计时点 |
| D-3 | Low | 威胁模型总述中的联网范围及 CI 扫描表述滞后 | 当前适配器、ADR、工作流 |

合计 **13 项：High 2、Medium 4、Low 7，Critical 0**。包含验证体系和文档问题，不应全部称为运行时漏洞。

## 2. High

### H-5 JNI 缺少完整的 C++ 异常边界

**位置**：`engine-rime/src/main/cpp/zero_rime_jni.cpp`，尤其 `ToUtf8`、`ToJString`、`nativeInitialize`、`nativeCreateSession`、`nativeReadUpdate`、`nativeCandidatePage`。

文件有 17 个 JNI 导出入口，只有两处局部 `catch`，分别保护 OpenCC 转换和初始化中的 OpenCC 配置加载。它们没有覆盖整个入口及其中的 `std::string`、`std::vector` 分配。例如 `ToUtf8` 的 `reserve` / `push_back` 抛出 `std::bad_alloc` 时，没有外层处理；此结论不依赖猜测 librime 哪个 API 会抛异常。

**影响**：C++ 异常不会自动转成 Java 异常；逃出 JNI 可导致进程终止，绕过 Kotlin 的引擎降级处理。本轮未做 Android native 分配失败注入，不声称已实机触发崩溃。

**处理方向**：对 JNI 入口建立完整异常边界，以 RAII 保护异常前已取得的字符串、上下文等资源；保留已挂起的 Java 异常，否则转换为固定且无正文的错误。原报告按两个 `catch` 推算“两个入口安全”不准确。

### H-7 存档 universal 包缺库，校验未验证逐 ABI 的必需库集合

**位置**：`tools/package-test-apk.ps1:110`；`tools/resources/verify-apk.py:45` 起的 native 检查。

本轮直接枚举本地存档：

`app/build/outputs/test-apk/20261113-135806/zero-fish-input-0.7.4-debug-universal.apk`

SHA-256：`fb27982f88ad25cd1de324c47dad5ae3522f8145da34bd32e3ef912470962838`。

| ABI | ONNX 两个库 | `libzeroinput_rime.so` | `libc++_shared.so` |
| --- | --- | --- | --- |
| arm64-v8a | 有 | **缺失** | **缺失** |
| armeabi-v7a | 有 | **缺失** | **缺失** |
| x86_64 | 有 | 有 | 有 |

对照存档 `20261031-100310/zero-fish-input-0.7.1-debug-universal.apk` 的三个 ABI 均包含四个库。

**实际缺口**：aapt2 的 `native-code:` 反映包内存在 native 库的 ABI，不能证明每个 ABI 都包含 Rime。ARM 的 ONNX 库足以使 ABI 集合完整。原报告把它解释为独立于 ZIP 内容的“元数据声明”不准确。Python 校验器验证了 ONNX 库集合和哈希，没有对应的 Rime / C++ 运行库必需项断言。

**影响和范围**：上述存档包在 ARM 设备上无法加载对应 Rime 库，不能提供预期 native 中文转换。未证明它由当前 HEAD 精确构建、实际被用户安装、远端仍在分发，或所有 universal 构建都会如此；内部构建根因未确定。

**处理方向**：按预期 ABI 检查每个必需库，再以重新构建的 ZIP 条目验收。`-PrequireRime=true` 检查源码存在性，不能替代产物逐库检查。

## 3. Medium

### M-6 含 API key 的完整配置长期留在应用级缓存

**位置**：`user-data/src/main/kotlin/dev/zeroinput/userdata/AiConfigurationRepository.kt:53`、`:58`、`:85`；`app/src/main/kotlin/dev/zeroinput/ime/AppGraph.kt:235`、`:266`、`:321`；`app/src/main/kotlin/dev/zeroinput/ime/ai/AiConfigurationState.kt:8`。

仓库 `cached` 保存完整配置，包含所有 provider 的明文 `apiKey: String`。首次 `read()` 和每次 `write()` 后持续持有。AppGraph 初始化即读取并发布完整对象，不要求 AI / 联网已开启。

`revokeAiConfiguration()` 确实撤销配置状态、协调器和任务代次，**不能据此声称旧配置仍能发送请求**。真实问题是仓库继续持有旧凭据；后续控制操作失败或被拒绝时也未丢弃该引用。成功写入“关闭 AI / 联网但保留 provider”的配置后，仓库和已发布快照也仍含凭据。

**影响**：不符合 AGENTS.md §4 对敏感明文长期进程缓存的限制，扩大凭据堆存留范围；未发现日志、联网旁路或跨进程读取。

**处理方向**：分离非秘密配置与按需凭据，同时控制仓库、已发布快照及失败路径的生命周期。仅增加仓库 `invalidate()` 不足以移除其他快照引用；丢弃引用也不能保证擦除 JVM `String`。

### M-18 网络静态门禁漏检连接与 DNS API

**位置**：根 `build.gradle.kts:30` 的 `networkViolation`。

提取当前正则后，以下完整限定名均不匹配：`java.net.ServerSocket(0)`、`java.nio.channels.ServerSocketChannel.open()`、`java.nio.channels.AsynchronousSocketChannel.open()`、`java.net.InetAddress.getByName(...)`。对照 `java.net.Socket(...)` 能匹配。前三类提供监听/连接能力，DNS 解析也可产生网络通信。

`URLConnection` 类型名同样漏匹配，但单独引用类型不等于执行联网，因此不将它列作另一种已发生网络操作。

**影响**：这是拦截将来未批准网络路径的自动检查缺口，不是当前已有未批准联网的证据。探针只匹配文本，没有发出网络请求。

**处理方向**：补通信能力分类和门禁负向用例；保留 `URI`、TLS 异常等纯数据用途。不要按原建议直接封禁整个 `java.nio.channels` 包而误伤本地文件 I/O。

### L-7 语言包文本行可在文件上限内制造大量中间对象

**位置**：`language-pack/src/main/kotlin/dev/zeroinput/languagepack/LanguagePackEngine.kt:142`、`:157`、`:223`。

单文件上限为 16 MiB，但 `parseLine` 对整行执行 `split('\t').map(...).filter(...)`，后备空白解析也先完整拆分，没有原始单行长度或字段数上限。50,000 条输出词条上限无法限制第一行的中间字段分配。

**复现**：对当前 `compileDebugKotlin` 输出，通过反射调用真实 `LanguagePackDictionaryLoader.parseLine`。固定输入 `"a\t"` 重复 4,000,000 次，共 8,000,000 ASCII 字节，在 `java -Xmx128m` 下得到：

```text
REPRODUCED_PARSE_OOM bytes=8000000 heap=134217728
```

2,048 字节的小行对照正常解析。输入低于现有文件上限，却可产生内存放大。本轮是独立 JVM 的解析器级复现，未运行 Android 整包导入，不能称为已确认的真机进程崩溃。

**处理方向**：拆分前限制原始行长及字段数，或仅扫描需要的有界字段；回归覆盖大量制表符/空白，而非只检查最终词条长度。

### L-27 JNI 分配失败后仍执行不安全的后续调用

**位置**：`engine-rime/src/main/cpp/zero_rime_jni.cpp:104` 的 `ToStringArray`，以及 `:330`、`:336`、`:404` 起的调用序列。

`ToStringArray` 未检查 `FindClass`、`NewObjectArray`、`ToJString` 的结果或待处理异常，就继续创建数组、写数组。调用方也可能在第一次转换失败后继续第二、第三次数组转换；`nativeReadUpdate` 连续创建字符串时也没有逐次停止。

**影响**：Java 分配失败已挂起异常时继续使用空数组，或调用不允许在异常挂起时调用的 JNI API，可能导致 CheckJNI 中止或其他 JNI 故障。此项与 H-5 的 C++ 异常逃逸属于不同失败通道；本轮未做设备分配失败注入。

原报告把每个未显式检查的 `NewObject` 都算作问题过宽：失败后只释放允许释放的局部引用并返回空值，可以正常保留原异常。本条仅保留失败后继续进行非清理操作的路径。

**处理方向**：每个可能失败的 JNI 分配/查找后检查结果与 `ExceptionCheck()`，失败立即清理返回，不再次分配或调用转换助手。

## 4. Low：验证体系、文案和文档

### M-14 缺少跨实现的一致引擎契约测试集

AGENTS.md §5.3 与 ADR 0001 要求一致的引擎契约验证。当前英语、降级拼音、语言包有各自特性测试，native Rime 还有 `RimeSessionVerifier.verify` 预热探针，不能概括为“没有任何引擎测试”。但 `engine-api/src/test`、`engine-english/src/test`、`engine-rime/src/test`、`language-pack/src/test` 未提供共同执行 `start`、按键、候选选择、翻页、`reset`、`close` 的跨实现契约集。

这是验证体系缺口，按 Low 记录，不推导当前已发生选词错误。应补共同契约及 native 平台运行方式；关闭后继续调用的行为应先定义，不能未经契约约定就认定是现存缺陷。

### M-23 第三方清单遗漏 kotlinx-coroutines

本轮 `releaseRuntimeClasspath` 解析到 `kotlinx-coroutines-android`、`kotlinx-coroutines-core` / `core-jvm` **1.6.4**；`THIRD_PARTY.md`、`NOTICE`、`SOURCES.md` 未列出。Kotlin runtime 2.1.21 条目未说明这个独立版本的传递组件。

应补组件、版本、来源及适用许可，并核对实际再分发义务。项目已有通用 Apache-2.0 文本，不能由缺具名条目推断为无许可证或已构成侵权。本轮未新构建 Release APK。

### L-14 Android 8.x 自身空项去重可能误标外部替换项

**位置**：`app/src/main/kotlin/dev/zeroinput/ime/clipboardguard/AndroidSystemClipboard.kt:15`、`:27`；`ClipboardGuardSession.clear()` 后的立即复查。

API 26/27 写空文本后设置 `captureOwnEmptyTimestamp = true`，把下一次元信息读取的时间戳无条件记为自身空项并返回 `null`。若另一应用在写空之后、复查之前替换当前项，替换项时间戳会被记入 `ownEmptyTimestamp`，后续相同时间戳继续被隐藏。上层可能报告清空成功，并在该项不变、适配器仍存活期间漏提醒。

窗口在同步清空后的复查处就存在，并非原报告所说必须等下一次回调。本轮未在 API 26/27 真机复现时序。

应补替换项交错的元信息级回归，评估有界去重；不能按原建议读取长度/正文判空。平台没有原子的“清空并返回自身时间戳”操作，改进后仍须承认竞态限制。

### L-42 语言键长按提示没有本地化

**位置**：`ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/ZeroInputView.kt:183`、`:1393`。

`toolbarButton("中", "切换中英文")` 的长按闭包捕获构造参数，英文界面仍提示“切换中英文”。实际标签与无障碍描述会在 `renderEditorLanguage` / `renderDraftLanguage` 中被资源覆盖，不能一并判为无法本地化；字号是否一致属于设计选择，不作为缺陷。

应让长按使用资源化的当前操作描述，并保留敏感编辑器状态对应的准确提示。

### D-1 架构依赖图遗漏两条实际边

`docs/architecture.md:24` 的依赖图未明确列出 **`ime-ui -> ime-core`**、**`app -> security`**，分别可由两模块 `build.gradle.kts` 的直接依赖确认。

原报告声称缺少的 `app -> ai-api`、`app -> model-scoring`、`app -> engine-dictionary` 当前图中均已列出。只需补实际遗漏的两条；未发现由此形成循环依赖或向业务层泄漏 librime 类型。

### D-2 私密粘贴认证导航时限文档错误

`docs/adr/0012-keyboard-private-copy-and-foreground-cleanup.md:63` 写认证导航 60 秒、成功后授权 30 秒。实际 `AuthenticationBroker.kt:91` 的请求期限和 `SecurePasteCoordinator.kt:50` 的初始期限都是 **30 秒**，成功后再计 30 秒。

应把文档对齐已确认的 30 秒边界，不因旧文档直接延长运行时授权；这不是认证绕过。

### D-3 威胁模型总述的联网范围和 CI 表述滞后

`docs/threat-model.md:108` 写“CI 扫描项目源码”，`:110` 写“AI 网络传输仅允许 OpenAiCompatibleProvider”。当前另有 ADR 0033 已批准的独立 `AiRemoteHttpsTransport`；现有两个工作流 `public-dictionaries.yml`、`publish-resources.yml` 均未调用 Gradle `privacyCheck`。本地验证/打包可调用门禁，不等于现有 CI 已调用。

应更新总述，区分模型、远程工具、公开词典适配器及门禁实际运行位置。模型端点禁止 query 本身正确，Brave / 词典需要 query 的已批准行为不构成绕过该规则。

## 5. 本轮验证与证据

实际成功执行：

```powershell
./gradlew.bat :ime-core:testDebugUnitTest `
  --tests 'dev.zeroinput.ime.core.AsyncCandidateRankerTest' `
  :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency okio `
  -PrequireRime=true --no-parallel

./gradlew.bat :language-pack:compileDebugKotlin `
  :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency kotlinx-coroutines `
  -PrequireRime=true --no-parallel
```

排序器 **11 个测试通过，0 失败、0 错误、0 跳过**，既有测试覆盖禁用后的重建、关闭时的 worker 释放。Okio 实际为 **3.6.0**，由固定 OkHttp 4.12.1 传递引入；没有版本目录独立条目不等于实际版本不符。语言包编译输出为 `UP-TO-DATE`，独立 Java 探针调用该实际输出。另对存档 APK 直接枚举 ZIP、对当前网络正则执行文本探针。

SharedPreferences 线程契约额外对照本机 SDK 的 `SharedPreferences.java` 与 `SharedPreferencesImpl.notifyListeners`：回调在主线程执行，后台写入会投递主线程。原报告从“后台 apply 在写入线程同步派发”推导的 View 跨线程和锁序问题不成立。

私密草稿提交链也已核对：`ZeroInputService.privateDraftCommitTarget` 调用 `SessionConnectionBinding` 保存的原始平台 `InputConnection.commitText(value, 1)`，并不经过另行构造的 `AndroidEditorConnection.commitText(text)` 包装器，所以不会由这条调用触发包装器的 `onCommitted` 播报回调。原 Critical 的四步链条混淆了两种连接；目标编辑器收到用户明确提交的文本不属于该链条所指的输入法主动播报。

证据位于 `.tmp-evidence/`：

| 文件 | 用途 |
| --- | --- |
| `audit-recheck-20261118-ranker-okio.log` | 定向测试与 Okio 解析 |
| `audit-recheck-20261118-coroutines.log` | 语言包编译输出与协程依赖解析 |
| `audit-recheck-20261118-network-probes.log` | 网络正则匹配结果 |
| `audit-recheck-20261118-apk-entries.json` | 两个存档 APK 的哈希与 native ZIP 条目 |
| `AuditDictionaryLineProbe.java`、`audit-recheck-20261118-line-probe.log` | 行解析器 JVM 探针与结果 |
| `2026-11-17-code-audit.before-recheck-20261118.md` | 更新前原报告备份，仅供历史对照 |

未重新执行全量单元测试、privacyCheck、Lint、Debug/Release APK 构建、三 ABI 构建或设备测试。本轮仅修改报告，不把原报告的历史成功记录当作本轮通过结果。native 异常、JNI 分配失败、Android 8.x 剪贴板交错的设备表现仍需定向验收。后续代码变化后应按路径和符号定位。
