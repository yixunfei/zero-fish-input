# ZeroInput 重构修复复查（第二轮独立核查，2026-11-18）

| 项目 | 内容 |
| --- | --- |
| 复查对象 | `2c86668`（`chore: backup refactor review baseline`）之上的工作区改动：55 个修改文件 + 4 个新增文件，`+766 / −151` |
| 被复查的声明 | `docs/audits/2026-11-18-refactor-fixes.md`（下称"处置文档"）声称 16 项已处理、D-5 撤回 |
| 复查方式 | **不采信处置文档结论**，逐项回到当前源码取证；同时用 `--rerun-tasks` 强制复跑单测与隐私门禁 |
| 判定口径 | 已修复 / 部分处理 / 接受现状（说明理由）/ 撤回 |
| 修改范围 | 仅新增本文档，并在原审查文档顶部加一行交叉引用；**未改动任何产品源码、测试、构建脚本或清单** |
| 未覆盖 | 真机与模拟器、3 ABI 原生构建、instrumented 测试、JNI 故障注入、Lint、Release 变体单测 |

> **结论**：15 项确认修复，1 项（D-3）保留现状且理由成立，1 项（D-5）撤回成立——我原先的"死代码"判断有误。**未发现新的中/高等级缺陷**，也未发现隐私边界被放宽。A-1 仍保留一个有界但用户可见的退化窗口（见 §5.1），D-2 的迁移范围与文档自述一致但并未覆盖全库。

## 1. 复查环境的一个插曲

首次 `./gradlew` 直接失败，报 `E:\gradle-cache\caches\journal-1\journal-1.lock (拒绝访问)`，随后是 `jars-9.lock`、`modules-2.lock`。排查结论：另一条构建（`K:\zero-player\android`）与残留守护进程同时持有同一个 Gradle 用户目录的缓存锁，与本次改动无关。执行 `./gradlew --stop` 释放后重跑成功。

记录这一点是因为它影响取证可信度：如果只看**上一次**构建留下的 XML，会把"别处跑过的结果"当成"当前代码的结果"。本轮所有数字都来自我自己的 `--rerun-tasks` 运行。

## 2. 结论摘要

| 编号 | 判定 | 取证要点 |
| --- | --- | --- |
| A-1 | 已修复 | `editsUnproven` 与 `dictionaryEditsApplied` 拆分；缓存未命中不再清空候选；空格改为提交原始拼音 + 空格 |
| B-1 | 已修复 | 解密时选项入口直接提示"参数由载波决定"，`prepare()` 以载波为准覆写 `options.mode/kdf` |
| B-2 | 已修复 | 预检改用 `submittedChars()`；派发前对同一份 owned 缓冲再校验一次 |
| B-3 | 已修复 | 密钥文件读取上限 5 s → 120 s，并区分超时提示 |
| B-4 | 已修复 | 认证前草稿预算 120 s，认证成功后另起 30 s 确认窗；写缓冲在确认点击时才构造 |
| B-5 | 已修复 | `OutOfMemoryError` 转为可投递的失败结果；其他 `Error` 在 `finally` 中先投递失败再传播 |
| C-1 | 已修复 | 隐写面板打开时工具栏 `☰` 隐藏 |
| C-2 | 已修复 | 只有含载波标记的文本才显示"本地解密"，且 `takeForStego()` 二次校验 |
| C-3 | 已修复 | `showReview()` 显式恢复动作与载波按钮可见性 |
| C-4 | 已修复 | 两处启动点均捕获拒绝并按 token 精确丢弃交接 |
| D-1 | 已修复 | 词典流式解析跳过超长标量但完整校验语法；清单与对象键仍硬失败 |
| D-2 | 部分处理 | 仅迁移 3 个具名路径，`runCatching` 仍有 152 处；处置文档已如实声明范围 |
| D-3 | 接受现状 | 4 份模块内 `recoverable` 保留并补充策略注释，未新增公共 API |
| D-4 | 已修复 | `close()` 仅在已初始化时销毁交接，避免惰性创建 |
| D-5 | **撤回** | `enqueueClear(await=true)` 被拒绝时同步执行并返回 null，`?: return` 可达且正确 |
| E-1 | 已修复 | `LinkageError` 收敛为 `EngineOperationFailure`，按键回退到编辑器路径；其他 `Error` 仍传播 |
| E-2 | 已修复 | `client`/`response` 加 `@Volatile`，prepare/send/execute 三处补取消检查 |

## 3. 逐项复核

### 3.1 A-1（输入正确性）

**当前实现**

- `engine-api/.../PagedPersonalizationStore.kt:14` 新增 `editsUnproven`，默认 `false`；`dictionaryEditsApplied` 保持原语义。
- `ime-core/.../PersonalCandidatePaging.kt:104`：`editsPending = loaded.editsUnproven`。只有"抑制元数据尚未准备"才清空候选；`ready=false` 的普通未命中不再清空。
- `app/.../DictionaryPersonalizationStore.kt:22-42`：在 worker 上一次性准备不可变抑制集；`pendingPage()` 在无快照时才返回 `editsUnproven=true`，否则返回已备好的集合。
- `ime-core/.../InputSessionController.kt:172-176`：`editsPending` 时执行 `commitUnconsumedKey(..., " ")`，即提交原始组合串再补空格，不再丢弃按键。

**判定**：原问题的两个根因（"未缓存"冒充"编辑未落地"、空格静默丢弃）都已消除。新增回归覆盖三种状态：`DictionaryCandidatePagingTest.pendingAnswersKeepFilteredNativeRowsAndTheLastResolvedPersonalPage`（未命中保留上一页）、`DictionaryPersonalizationStoreTest.cacheMissesReadOnlyPreparedSuppressionAndInvalidationRevokesIt`（未命中也只读快照、失效后回到 unproven）、`EngineLinkageLifecycleTest.rawSpaceAndPersonalCandidateResetFailuresNeverReplayAcceptedText`（unproven 时空格输出 `["ni", " "]`）。

**残留**：见 §5.1。

### 3.2 B-1…B-5（隐写面板）

- **B-1**：`StegoOptions.kt:43` 在 `decrypt=true` 时直接提示并不再构建选项列表；`StegoWorkspace.kt:187-193` 解密分支用 `submittedChars()` 取载波、`inspect()` 得到权威 `mode/kdf` 后写回 `options`。原先"改了必然失败且无法恢复"的死路消失。
- **B-2**：`prepare()` 与 `enqueue()` 现在都走 `submittedChars()`（内部先 `finishComposition()`），且 `enqueue()` 在 `StegoWorkspace.kt:207-214` 对**实际交给 worker 的那份缓冲**再校验 `mode/kdf`，覆盖"代价确认对话框期间被编辑"的窗口。`submittedChars()` 返回 `CharArray` 副本（`LocalDraftInput.kt:139-143`），清零不会波及界面草稿。
- **B-3**：`StegoSourceActivity.kt:301` 改为 `resetDeadline(120_000, stego_file_timeout)`；`StegoKeyFileRead` 本身无内部 5 s 限制，超时由外层 deadline + `CancellationSignal` 负责，取消链路完整（`discard()` → `closeFile()` → `signal.cancel()`）。
- **B-4**：`authenticate()` 不再在认证前设置 30 s（原 `:193` 已删除），保存入口 `:142` 给到 120 s；认证成功后 `:206` 另起 30 s 确认窗并单独提示。`StegoHandoff.Request.takeAddition()`（`:24`）把写缓冲推迟到确认点击，`PendingClipboardAddition` 的 30 s 生命周期因此从点击起算，不再与认证窗口叠加消耗。
- **B-5**：`StegoCryptoTask.kt:29-36` 的 `compute()` 捕获 `OutOfMemoryError` → 清空部分结果并置 `memoryUnavailable`；`CancellationException`/`InterruptedException` 清理后重抛；`:61-73` 在 `finally` 中先 `offer(delivery)` 再投递，`publish()` 对空结果调用 `gate.fail(token)`，相位回到 `EDITING` 并提示失败，不再卡 `WORKING` 120 s。

**判定**：五项均修复。B-4 的"两个 30 秒窗口"现在变为"120 s 草稿预算 + 30 s 确认窗"，语义与 ADR 0039 新增段落一致。

### 3.3 C-1…C-4

- **C-1**：`ZeroInputView.kt:1295` 加上 `&& !isTextStegoOpen`，与 `showTools()` 的提前返回一致。
- **C-2**：`ClipboardImportRequest` 新增 `allowsStegoImport`，由 `ClipboardImportText` 的载波标记检测传入；`takeForStego()` 在 `:42-45` 双重校验。普通文本既看不到按钮，也无法消费本次导入。含标记但损坏的文本仍显示按钮（会失败并给出提示），这是有意的"可诊断"取舍。
- **C-3**：`ClipboardImportView.kt:114-124` 的 `showReview()` 先 `clearText()` 再逐项设置可见性，`stegoAction` 不再依赖上一次状态。
- **C-4**：`ZeroInputService.kt:1255-1266` 与 `ClipboardImportActivity.kt:127-139` 均捕获 `RuntimeException`，调用 `stegoHandoff.discard(token)`；`discard()` 只在 token 匹配时清空（`StegoHandoff.kt:62`），旧启动失败不会误删新草稿。同一轮还修了 `take()` 对不匹配 token 的过度清理（见 §3.5 附带修复）。

### 3.4 D-1…D-5

- **D-1**：`LanguagePackJsonReader` 增加 `skipOversizedScalars`（仅词典路径开启）；`string()` 超限后丢弃缓冲但继续校验转义与结构，结尾回调 `oversizedScalar()`；数字改为显式状态机（`:133-166`），超长数字同样只跳过不保留。键与清单仍走默认 `false`，超限即失败。测试覆盖 10 万字符值、超长数字、坏转义、结构错误、后续有效条目、以及清单侧仍拒绝。
- **D-2**：仅 `EngineWarmupCoordinator`、`EngineWarmupResultDelivery`、`SettingsRepository` 的监听回调迁移为 `recoverable`，并把清理移入 `finally`。现状统计：`runCatching` 152 处、`recoverable` 46 处、`recoverableEngine` 18 处、`engineOperation` 10 处。处置文档明确写了"不是全库审计"，与代码一致。
- **D-3**：4 份 `recoverable` 保留，仅补注释说明"模块内故意重复，策略需对齐"。判为接受现状：不引入公共 API 的取舍合理，但代价是策略漂移风险仍在。
- **D-4**：`AppGraph.kt:94-97` 拆出 `stegoHandoffDelegate`，`:496` 改为 `if (isInitialized()) destroy()`，`close()` 不再注册一次性监听器。
- **D-5**：撤回成立。`enqueueClear(await=true)` 在 `RejectedExecutionException` 分支置 `runSynchronously`、返回 `null`，随后同步执行 `operation()`；`clearAndAwait()` 的 `?: return` 正是这条路径，原判断漏看了拒绝分支。

### 3.5 E-1 / E-2

- **E-1**：`ime-core/.../RecoverableOperation.kt:17-28` 新增 `recoverableEngine`（在 `recoverable` 之上捕获 `LinkageError`），`engineOperation` 改为经它包装，`EngineOperationFailure` 的 cause 放宽为 `Throwable`。`handle()` 的 `catch (EngineOperationFailure)` 因此能接住 `UnsatisfiedLinkError` 并走 `recoverFromEngineFailure` + `handleEditorFallback`。`EngineLinkageLifecycleTest` 6 条覆盖 handle/close、回车后不重放、raw 空格、个人候选、reset/隐私收紧、启动失败、prepared 一次性释放、以及取消/中断/`AssertionError` 仍传播。
- **E-2**：`OpenAiCompatibleProvider.kt:274,277` 加 `@Volatile`；`:297`（prepare 后）、`:305`（send 入口）、`:316`（execute 返回后）三处取消检查，任一命中都会 `disconnect()` 后再抛 `IOException`。`PlatformExchangeCancellationTest` 做 40 轮竞态，断言 `resourcesReleased()`（executor 已关闭且连接池为空）。
- **附带修复**（处置文档"第二次自检"）：`StegoHandoff.take()` 现在对不匹配 token 只拒绝调用方、不关闭当前草稿；`inputView` 中回车/选词后 reset 失败也不再重放。两处均有回归（`StegoHandoffTest` 2 条，其中一条复现了"旧 token 擦掉新草稿"）。

## 4. 验证证据（本轮自跑）

```bash
./gradlew :security:testDebugUnitTest :ime-core:testDebugUnitTest \
  :language-pack:testDebugUnitTest :app:testDebugUnitTest \
  privacyCheck testPrivacyBoundary --rerun-tasks --console=plain
# BUILD SUCCESSFUL in 3m / 219 actionable tasks: 219 executed
```

| 模块 | 测试类 | 用例 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| security | 6 | 27 | 0 | 0 | 0 |
| ime-core | 23 | 199 | 0 | 0 | 0 |
| language-pack | 13 | 64 | 0 | 0 | 0 |
| app | 106 | 532 | 0 | 0 | 2 |
| **合计** | **148** | **822** | **0** | **0** | **2** |

与第一轮（805 例）相比新增 17 例，分布与改动一致：ime-core +8、language-pack +2、app +7。2 个跳过均为需要真实 provider 的 opt-in 用例（`AiProviderLiveHostTest.realCatalogAndPublicGenerationRequireSeparateSuccess`、`AiProviderEditorTest.liveEditorFetchesSelectsAndSavesOnlyAfterExplicitActions`），与本次改动无关。新增/改动的关键类全部通过：`EngineLinkageLifecycleTest` 6、`PlatformExchangeCancellationTest` 1、`StegoHandoffTest` 2、`DictionaryPersonalizationStoreTest` 8、`DictionaryCandidatePagingTest` 6、`LanguagePackDictionaryJsonTest` 5、`LanguagePackJsonBoundaryTest` 14、`EngineWarmupCoordinatorTest` 10、`EngineWarmupResultDeliveryTest` 4。

`privacyCheck` 与 `testPrivacyBoundary` 属于 `doLast` 自检（含负向样本），随构建通过即表示：未新增权限/导出组件、合并清单仍 `allowBackup=false`、系统剪贴板正文读取仍只允许指定适配器、native/构建脚本/数据资产扫描无违规。

与处置文档宣称的 1,145 例不冲突：那一次包含 engine-api/engine-english/engine-dictionary/ai-api/user-data/model-scoring 与 Release 变体，本轮只跑了受影响的 4 个模块的 Debug 套件。

## 5. 新增观察（均未达缺陷标准）

### 5.1 A-1 的退化窗口仍在，但已有界且不再是"丢键"

当抑制元数据尚未准备（冷启动首次预热前、`invalidatePreparedData()` 之后——例如刚保存词典编辑、切换设置、清空数据），空格会提交"原始拼音 + 空格"，而不是转换。这比原先的静默丢弃好，但仍是可见退化：用户会看到 `nihao ` 而不是 `你好`。

窗口长度取决于一次 worker 预热（`schedulePreload()` 在首个查询即触发）。若希望进一步收敛，可考虑在 `invalidatePreparedData()` 里主动预热，或在等待期内把空格排队、查询返回后补发——后者会引入"按键延迟生效"的新复杂度，不建议在本次范围内做。

### 5.2 解密会改写共享的 `options`

`prepare()` 解密分支把载波的 `mode/kdf` 写回 `options`。解密一个"密钥文件"模式的消息后切换到加密，加密模式会沿用文件模式与对应 KDF，直到用户手动改回。这是"记住上次参数"的延伸，不算错误；但如果产品希望加密默认回到密码模式，需要额外复位。

### 5.3 D-2 的残余面仍然很大

`runCatching` 仍有 152 处，其中相当一部分位于输入路径之外的后台任务。处置文档已声明范围，本轮也不把它们算作缺陷；但"取消在局部被吞掉"这一风险在未审计区域依旧存在，后续若继续收敛，建议按"是否会阻塞用户可见状态机"排序，而不是按文件逐个改。

### 5.4 C-2 的损坏载波仍显示解密入口

含标记但结构损坏的文本会显示"本地解密隐写文本"，点击后失败。这是有意的（用户需要知道"这串看起来像载波但解不开"），但失败提示目前是通用文案，没有区分"标记存在但信封损坏"。属于体验细节，不是缺陷。

## 6. 未验证范围

- **真机/模拟器**：`StegoSourcePlatformTest`（16 s 认证前 + 16 s 认证后）、`StegoKeyFilePlatformTest`（5.5 s 慢 provider）、`StegoVaultPlatformTest`、`ClipboardImportPanelTest` 等 instrumented 用例本轮未执行；处置文档记录的设备结果属于其自述，我未复核。
- **原生**：未重建 ARM/ARM64，未做 JNI 符号故障注入；E-1 的证据是 Kotlin 层的确定性 `LinkageError` 注入（这一点处置文档也如实标注）。
- **Lint 与 Release 变体**：未复跑；本轮结论不覆盖这两类检查。
- **真实 OOM**：B-5 的验证是投递边界上的故障注入，不是进程级内存耗尽。

## 7. 建议

1. 按当前状态可以收尾本轮重构；上述 15 项修复的边界与原审查建议一致，未引入新的放宽。
2. 若要继续收敛 A-1，优先做"失效时主动预热"，而不是给空格加排队语义。
3. D-2 与 D-3 属于长期维护项，建议单独立项，不与本次修复混在一起。
4. 设备侧仍建议补一次完整 instrumented 回归（含慢 provider、双 16 s 认证等待、OOM 注入），因为这三类恰好是本轮改动最多、且主机测试覆盖最弱的部分。
