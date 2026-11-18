# ZeroInput 代码审计报告（含 2026-11-18 三轮复核，当前结论见 §10）

| 项目 | 内容 |
| --- | --- |
| 审计对象 | `K:\zeroInput` 当前工作区源码（未假定干净工作区、未依赖 Git 基线） |
| 审计日期 | 2026-11-18（报告生成于 11:18） |
| 复核日期 | 首次静态复核：12:16–12:30（§8）；最终再核验：12:42–13:10（§9）；第三次独立核查：13:11–13:30（§10） |
| 报告生成方式 | 原审计独立重写；后续三轮复核均保留发现历史、更新处置状态并补充独立验证证据 |
| 审计类型 | 原审计为静态源码审计；最终再核验含源码/契约核对、回归测试、故障复现和构建/设备验证；第三次核查含独立 Gradle 复跑与固定堆对照实验 |
| 复核类型 | 20 项逐项复查；第三次核查对 §9 的动态证据独立重算，并对新增流式解析器做边界复现 |
| 审计基线 | 报告生成时的工作区源码，与 `HEAD` `3632434`（v0.7.6 发布记录）的产品源码一致 |
| 复核基线 | 当前未提交工作区；首次复核文件计数见 §8，第二轮改动见 §9，第三轮证据见 §10 |
| 重点 | 功能性 bug、逻辑错误、边界条件缺失、异常/错误处理、潜在安全与性能缺陷 |
| 修改范围 | 最终再核验修复构建工具门禁和 JSON 流式解析，补充 clear 契约、测试与对应文档；**第三次核查仅更新本报告并新增 `.tmp-evidence/` 探针/日志，未改动产品源码、清单、构建脚本或测试**；无提交或发布 |

> **最新结论（以 §10 为准）**：20 项中 **16 项已修复、1 项部分修复（L-9）、3 项复核撤回**；M-7、L-2 各有一个子项撤回。第三次独立核查新发现 **R-1**（L-9 残留：嵌套 JSON 词典的 50,000 条上限是「每容器帧」而非全局，合法 12,750,029 字节输入在 `-Xmx128m` 下稳定 OOM），详见 §10.3。§8 为首次静态复核历史，§9 为 12:42–13:10 的当时结论，二者的「0 项待修复」已被 §10 取代。无 Critical / High 残留；原问题描述及原行号保留用于追溯。

## 0. 方法与判定口径

- **取证方式**：逐文件通读小模块的全部主源码；对大型文件按职责读取完整函数体；对全库执行模式检索（`!!`、宽泛 `catch`、`runCatching`、时钟基准、主线程 I/O、无界集合、JNI 分配、Manifest 导出、Gradle 门禁正则）后逐条回到源码验证；对涉及 Android 平台契约的判断，与本机 SDK `D:/env/AndroidSDK/sources/android-37.0` 中的 AOSP 源码比对。
- **复核取证方式**：以 `git diff HEAD` 分组导出全部产品源码补丁（主源码 25 个文件、测试 14 个文件），逐项比对原发现描述的触发路径是否仍存在；读取新增辅助函数与调用方的当前完整实现；核对新增负向测试是否真正覆盖该路径；对可判定性存疑的结论（正则匹配、ADR 批准、平台契约）用独立实验或文档原文交叉验证。
- **原审计/首次复核的限制**：当时没有独立成功执行 Gradle、Lint、APK 或设备验证（历史锁失败见 §7）。最终再核验已补充独立运行和故障实验，不能把首次复核限制套用到最终结果，也不能把已通过的局部实验推广为全部运行时安全证明。第三次核查又独立复跑了定向 Gradle 任务与固定堆对照实验，并据此把 §9 对 L-9 的「已修复」改判为部分修复（§10.3）。
- **严重度定义**：
  - **Critical**：无需特殊条件即可造成敏感数据泄露、越权或不可恢复的数据丢失。本轮未发现。
  - **High**：在特定但可预期的条件下导致用户数据未被删除、功能永久失效或输入内容被错误提交。
  - **Medium**：可复现的功能/并发/边界缺陷，或明显削弱防护纵深与失败可判定性。
  - **Low**：健壮性、边界、可维护性缺陷与轻微功能限制。
- **行号**：原发现中的行号指向审计时源码（`HEAD` 基线）；逐项复核结论中的行号指向修复后源码；§8 保留首次复核时行号，新增实现和本轮验证见 §9。行号为区间时给出的是相关函数/代码块范围。
- **复核状态口径**：「已修复」= 已确认的原问题路径已消除并有实现/验证证据；「部分修复」= 原问题主路径已消除但仍存在可复现残留（§8 为历史判断，§10.3 的 L-9 为当前判断）；「复核撤回」= 原描述与源码、契约或批准决策不符。撤回子项与有意保留行为不作为未修复缺陷；未完成的平台测量单独列为验证限制。

## 1. 覆盖矩阵

| 模块 / 区域 | 本轮实际检查内容 | 结果 |
| --- | --- | --- |
| `security` | 6 个主源码文件全部通读（GCM envelope、Keystore、授权生命周期、AtomicFile 读写、别名隔离） | M-5、相关说明见 §6 |
| `engine-api` | 13 个文件全部通读（端口、不可变模型、Glide/分页/频率契约） | 未发现高置信缺陷 |
| `engine-english` | 4 个文件全部通读 | L-1 |
| `engine-dictionary` | 6 个文件全部通读（滑行几何/解码、联想索引、QQ/Sogou/Rime 文本导入） | L-2 |
| `ime-core` | 17 个文件全部通读，含 987 行 `InputSessionController` 与隐私策略 | M-1、M-2、M-3、M-8、L-7 |
| `ime-ui` | 文件清单与关键交互实现（`BackspaceRepeater`、`KeyboardPanel`/`ZeroInputView` 结构、面板尺寸/候选视图）抽查 | 未发现高置信缺陷 |
| `engine-rime` | `RimeRuntime` 状态机、`RimeAssetInstaller`/`RimeConfigurationInstaller` 结构、全部 464 行 JNI 与 2 个 JNI 头文件 | L-6、M-8 |
| `user-data` | 加密仓库、格式解析、AI 配置/会话/上下文库结构，`SecureClipboardVault` 544 行全文 | M-6、M-7、L-8 |
| `language-pack` | 清单解析、词典加载、引擎、安装器、路径策略、注册表全文 | M-4、L-5、L-9 |
| `app`（组合根 / 输入服务） | `AppGraph` 凭据状态与删除协调、`ZeroInputService` 生命周期与代次校验、`AndroidEditorConnection` 全文 | M-1、M-4、L-4、§6 |
| `app`（AI / 网络） | Provider 连接与超时、SSE/JSON 响应边界、远程策略、配置发布与撤销 | M-4、M-5(部分)、L-3 |
| `app`（剪贴板 / 认证） | 认证代理、两阶段粘贴同意、系统剪贴板适配器、防护运行时全文 | M-5、L-8、§6 |
| `app`（设备代理 / 阶段 6） | 传感器采集、通知/权限边界、`AndroidManifest.xml` 全部组件 | L-3、§6 |
| 构建与门禁 | 根 `build.gradle.kts` 的 5 类违规检测、`privacyCheck` 任务、合并清单校验 | M-4 |
| 资源与配置 | `AndroidManifest.xml`、`local.properties` 中的 SDK 路径、模块 `build.gradle.kts` 的 minSdk | §6 |
| **首次复核增补** | 4 份 `RecoverableOperation.kt`、修复后 `InputSessionController`/`QueuedPersonalizationStore`/`OpenAiCompatibleProvider`/`build.gradle.kts` 的完整相关函数体、14 个改动测试与 6 个新增源码文件、`docs/adr/0024`、`ZeroInputService` 三处 `setLanguage` 调用上下文 | §8 |
| **最终再核验增补** | 自有 CMake/主机脚本/catalog 门禁、JSON 流式 reader/词条折叠、clear 公开契约、真实哈希负向用例、固定堆 OOM 红绿实验与 52 项平台回归 | §9 |
| **第三次独立核查增补** | 当前门禁全文与自测、JSON 事件 reader/折叠器/引擎调用侧全文、`HEAD` 基线解析器逐形状比对、独立 Gradle 复跑、APK 哈希与测试结果重算、嵌套 JSON 固定堆对照实验 | §10 |

## 2. 发现总览（第三次核查后的当前状态）

| 编号 | 严重度 | 主题 | 位置（审计基线） | 复核状态 |
| --- | --- | --- | --- | --- |
| H-1 | High | 异步清除被拒绝时静默丢弃，且个人词库永久停用 | `QueuedPersonalizationStore.kt:273-367` | 已修复 |
| M-1 | Medium | 输入链路 `catch (Throwable)` 过宽，任意 Error/取消都会提交组合原文并整会话降级 | `InputSessionController.kt:217-223, 951-961` | 已修复 |
| M-2 | Medium | `setLanguage` 静默丢弃未完成的组合输入 | `InputSessionController.kt:236-242` | 已修复 |
| M-3 | Medium | 词典编辑待处理时空格提交拼音原文而非等待 | `InputSessionController.kt:171-188` | 已修复 |
| M-4 | Medium | 隐私门禁只扫 `*/src/**` 的 kt/java/xml；批准适配器整文件放行 | `build.gradle.kts:30-40, 458-473` | 已修复 |
| M-5 | Medium | AI 请求体写入缺少写超时保护，取消路径依赖可被拒绝的 executor | `OpenAiCompatibleProvider.kt:191-221, 258-266, 278-303` | 已修复 |
| M-6 | Medium | `ContentObserver` 注册异常导致已注册观察者无法注销 | `ClipboardGuardRuntime.kt:187-190, 254-260` | 已修复 |
| M-7 | Medium | 旧格式 AI 配置被静默整体删除；配置校验把「有 provider 但未选中」判为不可用 | `AiConfigurationRepository.kt:98-120, 260-279` | 已修复（另一子项撤回） |
| M-8 | Medium | 多处 `runCatching`/`catch (Throwable)` 吞掉取消语义与 `Error` | 多文件，见条目 | 已修复 |
| L-1 | Low | 英语引擎用 Unicode `isLetter()` 接受非 ASCII 字母进入组合 | `EnglishInputEngine.kt:81-88` | 已修复 |
| L-2 | Low | 词库导入尾部奇数字节抛未包装异常；`readBytesLimited` 未处理 `read()==0` | `CellDictionaryParser.kt:36-53, 69-78` | 已修复（另一子项撤回） |
| L-3 | Low | 阶段 6 音频采集使用 `!!`；采集缓冲在提前返回路径未清零 | `DeviceStage6SensorCapture.kt:33-72` | 已修复 |
| L-4 | Low | `commitPairedText` 在光标设置失败时已提交文本却返回 false | `AndroidEditorConnection.kt:75-98` | 已修复 |
| L-5 | Low | 语言包 `staging` 清理正则与负 `nanoTime()` 组合边界 | `LanguagePackInstaller.kt:77-83, 248-252` | **复核撤回** |
| L-6 | Low | JNI `ToJString` 对空串使用 `NewString(nullptr, 0)`；UTF-8 解码未拒绝孤立续字节 | `jni_strings.h:53-84` | 已修复 |
| L-7 | Low | 候选窗口按 `text` 跨页去重，同形异音候选被吞掉 | `CandidateWindow.kt:45-54` | 已修复 |
| L-8 | Low | 加密写入 finally 无条件清零调用方数组；私密草稿 `beforeWrite` 被调用两次 | `EncryptedFileStore.kt:43-65`、`PrivateDraftEntries.kt:23-38` | **复核撤回（保留设计）** |
| L-9 | Low | 语言包校验把整个文件读成 String 再转 byte[] 做哈希 | `LanguagePackEngine.kt:147-177` | **部分修复（见 §10.3 R-1）** |
| L-10 | Low | 剪贴板删除先消费一次性授权，删除失败后需重新认证 | `SecureClipboardVault.kt:151-170` | **复核撤回（既定安全设计）** |
| L-11 | Low | `AsyncCandidateRanker.drain` 仅恢复 `Error` 路径，`draining` 缺少通用兜底 | `AsyncCandidateRanker.kt:93-108` | 已修复 |

审计时合计 **20 项：Critical 0、High 1、Medium 8、Low 11**（该分级只对原始 20 项）。
最终再核验（§9，12:42–13:10 当时结论）：已修复 17、撤回 3、待修复 0。
**第三次独立核查（§10，当前结论）：已修复 16、部分修复 1（L-9）、撤回 3、已确认待修复项 1（新增 R-1，见 §10.3）**。M-7 与 L-2 的另一子项已撤回，不计为残留；本结论限于这 20 项审计范围及 R-1 的复现条件，不表示穷尽全库缺陷或所有设备行为。

---

## 3. High

### H-1 异步清除任务被拒绝后静默丢弃，且个人词库被永久停用【复核：已修复】

- **位置**：`app/src/main/kotlin/dev/zeroinput/ime/personalization/QueuedPersonalizationStore.kt:273-367`（`enqueueClear`），重点 `:283`、`:333-361`、`:363-365`；关联 `:215-217`（`clear()`）、`:225-232`（`clearAndAwait()`）、`:370`（`schedulePreload`）。
- **问题描述**：`enqueueClear` 在提交清除任务前已设置 `ready = false`、`clearBlocked = true` 并清空缓存（`:282-287`）。当 `executor.execute` 抛 `RejectedExecutionException` 时：
  - `await == true` 分支（`:349-351`）置 `runSynchronously = true`，同步重试，行为正确；
  - **`await == false` 分支（`:353-359`）只把 `clearBlocked` 置 true 后返回 `null`，`operation` 从未执行，`delegate.clear()` 从未被调用**。调用方通过 `PersonalizationStore.clear()` 无法获知结果（接口返回 `Unit`）。
  - 由于 `clearBlocked` 保持 `true`，`schedulePreload()` 在 `:370` 直接返回，且 `ready` 为 `false`，此后所有个人候选查询与频率快照都不再恢复——直到进程重启。
- **影响范围**：用户主动「清除个性化数据」时，加密用户词库/学习数据可能仍留在磁盘，UI 却已经隐藏了全部个人数据并给出完成信号；同时个人词组、联想重排等功能在整个进程生命周期内不可用。这是数据删除语义与功能可用性的双重失效。
- **触发/复现场景**：`BoundedExecutors.singleThread(..., queueCapacity = 64)`（`:25-28`）队列被学习/查询任务打满时调用 `clear()`：例如在快速连续输入产生大量 `learn`/`recordUse`/预加载任务后立刻触发清除。构造方式可用注入的 executor 在单元测试中直接让 `execute` 抛 `RejectedExecutionException`。**当前设置主路径 `AppGraph.clearPersonalizationData()`（`AppGraph.kt:471`）调用的是 `clearAndAwait()`，会走同步重试，因此主路径不受影响**；风险集中在 `PersonalizationStore.clear()` 这一 public 接口路径及其未来的调用者。
- **建议方向**：让 `clear()` 在拒绝时也走同步控制路径或返回可判定结果；`clearBlocked` 只在「清除确实失败」时保持，并在清除成功后无条件复位；补队列饱和的负向测试。
- **复核结论（已修复）**：当前 `QueuedPersonalizationStore.kt:274-369` 中，`closed` 状态统一 `error(...)`（`:275-277`），`await == false` 且执行器拒绝时改为 **`throw error`**（`:355-362`），不再把「未执行的删除」报成已接受；`clearBlocked` 在失败时保持 `true` 属 fail-closed，显式重试 `clear()` 会重新入队，成功后 `:310` 无条件复位且 `:320-321` 恢复 `ready`。`clearAndAwait()`（`:226-233`）的同步兜底路径保留（`:350-354, :365-367`）。新增测试 `QueuedPersonalizationStoreTest.kt:248-265` 断言：拒绝抛 `RejectedExecutionException`、`delegate` 未被清空、显式重试后恢复读取。产品代码中仍只有 `AppGraph.kt:471` 的 `clearAndAwait()` 调用者，无 `PersonalizationStore.clear()` 直接调用者。**最终契约补充**：`engine-api/.../PersonalizationStore.kt` 的 `clear()` KDoc 已明确异步返回仅表示接受、拒绝/关闭会抛异常、旧读取保持关闭，以及用户可见成功必须使用实现的持久完成 API。

---

## 4. Medium

### M-1 输入链路捕获 `Throwable` 过宽，任何异常都会提交组合原文并整会话降级【复核：已修复】

- **位置**：`ime-core/src/main/kotlin/dev/zeroinput/ime/core/InputSessionController.kt:217-223`（`handle` 的异常边界）、`:951-961`（`recoverFromEngineFailure`）；关联 `AGENTS.md §10.3`。
- **问题描述**：`handle()` 用一个 `catch (_: Throwable)` 包住整个命令分派，包含 `OutOfMemoryError`、`StackOverflowError`、`LinkageError`、`CancellationException` 等。异常发生后无条件执行 `recoverFromEngineFailure(activeEngine)`：关闭引擎、把 `state.snapshot` 的组合文本通过 `commitRawComposition()` **提交到编辑器**（`:952-956`），随后 `handleEditorFallback(command)` 把当前按键再原样提交一次（`:787-805`）。最后 `publish(EngineSnapshot.Empty)`，该会话此后再无引擎（`engine == null` 走 fallback 分支 `:156-163`）。
- **影响范围**：一次与引擎无关的瞬时故障（渲染回调抛 Error、模型层 LinkageError、协程取消、内存压力）就会把用户未完成的拼音原文写进正在编辑的文档，并额外插入本按键的字面字符；随后整个会话失去中文转换能力，只能重启会话恢复。在敏感编辑器中这段组合文本本不应被提交。
- **触发/复现场景**：注入一个在 `engine.handle()` 或 `onStateChanged` 之外的路径抛 `Error`/`CancellationException` 的引擎或回调；或 native 库加载失败产生 `LinkageError`。用现有单元测试替换 `engineProvider` 即可稳定复现「组合原文 + 按键字符被双重提交」。
- **建议方向**：把捕获范围收窄到引擎可恢复异常（`Exception` 且排除 `CancellationException`），保留取消语义；`Error` 直接向上抛出或只做失效不提交；降级前区分「用户显式输入」与「内部故障」，避免把预编辑内容落到编辑器。
- **复核结论（已修复）**：`handle()` 现仅捕获 `EngineOperationFailure`（`InputSessionController.kt:222`）。新增 `ime-core/.../RecoverableOperation.kt:6-21` 定义 `recoverable`（只捕获 `Exception`，重抛 `CancellationException` 与 `InterruptedException` 并恢复中断标志，`Error` 不被捕获）与 `engineOperation`（只把**引擎调用内**的普通异常包装成 `EngineOperationFailure`）。所有引擎调用点（`handle` 命令分派、`applyKey` 的 `activeEngine.handle`、`reconvert`、`selectCandidate` 等）改为 `engineOperation`，`onStateChanged` 改用 `recoverable`（`:833-836`），`startEngine` 在致命失败时先 `closeSafely` 再重抛（`:927-936`）。新增 `InputSessionControllerTest.kt:28, 51, 76` 三组测试覆盖取消、`AssertionError`、渲染回调 Error：均不提交、不重放按键、不退役引擎。**保留行为**：`recoverFromEngineFailure`（`:956-966`）在**普通引擎异常**时仍提交组合原文、关闭引擎并整会话降级——现已限定为真实引擎调用失败，属设计取舍，不再是「任意 Error/取消」触发。另需注意取消异常现在会向 `handle()` 的上层传播，调用方需保留取消处理。 `PartialCompositionFallbackTest` 还覆盖真实普通引擎故障下保留已选片段与当前输入的受控降级；该可用性策略不等于原先任意 Error/取消被吞的问题仍存在，不再据此记为部分修复。

### M-2 `setLanguage` 静默丢弃未完成的组合输入【复核：已修复】

- **位置**：`ime-core/src/main/kotlin/dev/zeroinput/ime/core/InputSessionController.kt:236-242`（`setLanguage`），对照 `:226-234`（`switchLanguage`）。
- **问题描述**：`switchLanguage()` 在组合中时先 `connection.commitText(raw)` 保存用户输入，失败则放弃切换（`:230-231`）。而 `setLanguage(target, packKey)` 直接设置语言、`connection.clearComposingText()`（丢弃预编辑内容）并 `replaceEngine()`，**没有任何保存或失败回退**。`setLanguage` 是 public 方法，供设置、语言包/子类型切换等外部路径调用。
- **影响范围**：用户在拼音组合未上屏时切换语言包、子类型或由设置触发语言变更，已输入的拼音串被无声丢弃；`clearComposingText()` 在 `AndroidEditorConnection` 中还会触发 `onContextInvalidated`，连带使联想与模型上下文失效。
- **触发/复现场景**：输入 `nihao`（保持候选栏可见）→ 通过设置切换语言包或系统子类型 → 触发 `setLanguage` → 组合消失且编辑器无文本。可通过单元测试断言切换后 `connection` 未收到 `commitText`。
- **建议方向**：复用 `switchLanguage` 的提交/回退逻辑，或明确在切换前 `reset()` 并向 UI 给出「未完成输入已丢弃」的可判定状态。
- **复核结论（已修复，并更正原复现场景）**：当前 `InputSessionController.kt:226-243` 中 `switchLanguage()` 委托给 `setLanguage`，后者在切换前统一执行提交保护：组合中先 `commitText(raw)`，提交被拒则 `return`，保留原语言、原引擎与组合（测试 `InputSessionControllerTest.kt:51`、`:237` 断言提交 `"ni"` 且状态一致）。**原报告高估了服务层设置的即时暴露**：`ZeroInputService.kt:2011-2016` 与 `:2042-2048` 两处在组合中会把 `languagePackReloadPending = true` 延迟到空闲再切换，只有 `switchTo()`（`:1944`）会立即调用 `setLanguage`；即使如此，内部提交保护现在也已覆盖该路径。

### M-3 词典编辑待处理时按空格会提交拼音原文【复核：已修复】

- **位置**：`ime-core/src/main/kotlin/dev/zeroinput/ime/core/InputSessionController.kt:171-188`（`InputCommand.Space` 分支），重点 `:172-177`。
- **问题描述**：`if (personalPaging.editsPending) { handleEnter(activeEngine); return }`。`handleEnter()`（`:654-680`）在有组合时调用 `commitRawComposition()`，把 `rawInput`（拼音原文）提交，然后清空组合；它不会等待词典编辑查询完成，也不会插入空格。注释写的是「never discard an explicit space」，但实现既没有选词也没有插入空格，而是把未转换的拼音固化到文档。同样的早退分支还出现在 `:178-181`（`editsApplied && candidates.isEmpty()`）。
- **影响范围**：启用本地词典编辑的用户，在编辑查询尚未就绪（或过滤后候选为空）时按空格，会直接得到 `nihao` 这样的罗马字文本，而不是首选中文候选或空格；该结果还会进入 `commitPredictableText` 的联想上下文。
- **触发/复现场景**：注入一个 `suggestionPage` 返回 `ready=false, dictionaryEditsApplied=true` 的 `PagedPersonalizationStore`，组合中输入拼音后立刻按空格。可用 `ime-core` 单元测试复现提交文本。
- **建议方向**：`editsPending` 时保持组合并等待查询完成（沿用 `refreshPersonalization()` 的重发布路径），或至少让空格走明确的「提交原文 + 后缀空格」路径，避免静默改变语义。
- **复核结论（已修复）**：当前 `:171-188` 中 `editsPending` 分支改为直接 `return`（保留组合、不提交、不插空格），由刷新完成后用户再选词；`editsApplied && candidates.isEmpty()` 分支改走 `commitUnconsumedKey(activeEngine, ..., " ")`（`:89-90` → 定义于 `:854-869`），先 `flushUnconsumedComposition` 提交原文，再 `connection.commitText(" ")` 补上空格。`DictionaryCommitTest.kt:16-24` 的两条断言已分别更新为 `["nihao", " "]` 与「待处理时不产生任何提交」。**保留设计**：等待中的 Space 保留组合、不缓存按键，刷新后由用户再次选词；README 与测试已明确此行为，避免延迟按键在后续会话中被错误补发，不再是「静默把拼音固化到文档」。

### M-4 静态隐私门禁的扫描范围与信任边界存在缺口【复核：已修复】

- **位置**：`build.gradle.kts:458-473`（`scanPrivacySources`）、`:30-40`（`networkViolation`）、`:593-604`（`privacyCheck` 任务）。
- **问题描述**：
  1. `scanPrivacySources` 只遍历 `subprojects.map { it.file("src") }`，且只检查扩展名 `kt`、`java`、`xml`（`:459-464`）。**`src/main/cpp/*.cpp/.h`（JNI 与 librime 适配）、模块根目录的 `*.gradle.kts`、`tools/` 与 `gradle/` 下的脚本、`*.json`/`*.yaml`/`*.txt` 资源、根项目源码均不在扫描范围**。native 层完全可以直接调用 `getaddrinfo`/`connect` 或读取剪贴板相关系统能力而不触发任何一条规则，与 `AGENTS.md §14`「不得使用 native 绕行规避扫描」的意图不匹配。
  2. `networkViolation` 在 `path` 等于三个批准适配器时**整文件直接放行**（`:36`）：只要文件在白名单路径中，无论内部新增 `WebView`、`DatagramSocket`、代理设置还是自动重试，门禁都不会报错。门禁只验证「谁可以联网」，不验证「批准适配器内部是否仍遵守禁止重定向/代理/重试的约束」。
  3. `networkViolation` 的正则虽已覆盖 `ServerSocket`、`SocketChannel`、`InetAddress.getByName` 等，但 `java.net.InetSocketAddress`、`java.nio.channels.NetworkChannel`、以及通过 JNI 或 `Runtime.exec` 建立的能力仍无文本特征可匹配。
- **影响范围**：这是**自动检查的覆盖缺口，不是当前存在未批准联网的证据**。风险是未来在 native 层、构建脚本或已批准适配器内部引入未批准能力时不会在 `privacyCheck` 中被拦截。
- **触发/复现场景**：在 `engine-rime/src/main/cpp/` 增加一行 `#include <sys/socket.h>` 与 `connect(...)`，或在 `OpenAiCompatibleProvider.kt` 内加入 `http.instanceFollowRedirects = true`，运行 `privacyCheck` 均能通过（本轮为静态推断，未实际运行门禁）。
- **建议方向**：把 `cpp/h/hpp/cc`、`*.gradle.kts`、`buildSrc` 纳入扫描并为其设计独立规则；对批准适配器改为「能力白名单 + 必备约束断言」（例如强制断言 `instanceFollowRedirects = false`、`Proxy.NO_PROXY`、超时设置存在），并为门禁补负向用例。
- **复核结论（已修复）**：根 `build.gradle.kts` 已覆盖根/模块/`buildSrc` 源码、native、数据资源、Gradle 脚本，以及仓库自有 `CMakeLists.txt`、`.cmake/.py/.ps1/.sh/.toml`；`toolingTransportViolation()` 对 CMake 的下载/上传、FetchContent/ExternalProject、网络库和 shell 执行作独立限制；主机下载仅允许现有精确构建/资源工具路径，仍拒绝新增 socket、浏览器或替代 HTTP 客户端；版本 catalog 仅放行现有精确 OkHttp 声明。三个运行时适配器继续受禁止能力及必备连接约束检查。`testPrivacyBoundary` 已增补文件分类与负向用例；临时放入工具、CMake、catalog 三个违规文件后，真实 `privacyCheck` 同时报告并拒绝三者，随后移除测试文件并复跑通过。**边界说明**：固定版本/哈希的 `third_party` 按已选构建目标和供应链流程审核，未编译的上游网络示例不等于启用运行时联网，未对整个供应商目录机械套用自有代码规则；静态匹配仍不能替代依赖更新审核或阻止任意混淆。该局限不再列为已确认源码缺陷。

### M-5 AI 请求体写入没有写超时，取消依赖可被拒绝的执行器【复核：已修复】

- **位置**：`app/src/main/kotlin/dev/zeroinput/ime/ai/OpenAiCompatibleProvider.kt:191-221`（`exchange`）、`:206-209`（写 body）、`:258-266`（`Operation.abort`）、`:278-303`（`PlatformExchange`）。
- **问题描述**：`PlatformExchange.prepare()` 只设置 `connectTimeout` 与 `readTimeout`（`:283-284`），`timeout()` 也只改 `readTimeout`（`:297`）；`HttpURLConnection` 没有写超时 API。`exchange()` 在 `:208` 用 `http.output().use { it.write(body) }` 写请求体，此处若对端不读取请求体，写入会阻塞且不受 `readTimeout` 约束。唯一的兜底是 `scheduleTimeout` 触发的 `Operation.abort()`：它在 `:261-264` 通过 `cancellationExecutor.execute { http.disconnect() }` 中断连接，**但当该执行器拒绝任务时直接吞掉异常**，注释称「Worker finally/read timeout closes it」——而 `finally` 中的 `http.disconnect()`（`:219`）本身位于被阻塞的 worker 栈上，无法被执行到。
- **影响范围**：恶意或无响应的 HTTPS 端点可长期占住 AI worker 线程；`future.cancel(true)` 的线程中断通常不能打断阻塞中的 socket 写。用户配置的 `timeoutMs` 与 `operation.deadline`（`:158`）因此不能完整约束一次请求的总耗时。
- **触发/复现场景**：配置一个接受连接、读取请求头但不读请求体的端点，提交带较大附件（请求体接近 `DeviceAgentLimits.MAX_REQUEST_BYTES`）的生成请求，同时使 `cancellationExecutor` 饱和。需设备/网络环境验证，静态结论是「缺少写超时 + 兜底路径可失效」。
- **建议方向**：在写入前用独立的单调时钟看门狗直接持有 `http` 引用并在超时线程 disconnect；或改用支持写超时的传输实现；`cancellationExecutor` 拒绝时至少同步调用一次 `disconnect()`。
- **复核结论（已修复）**：`AiTransportExchange.kt:6-10` 把 `connect/output/timeout` 三段接口收敛为单次 `send(body)`。`PlatformExchange`（`OpenAiCompatibleProvider.kt:270-325`）改用项目已声明的 OkHttp（`gradle/libs.versions.toml:2,20`、`app/build.gradle.kts:167`），设置 `connectTimeout`/`readTimeout`/`writeTimeout`/`callTimeout` 四种超时，并保持 `Proxy.NO_PROXY`、`followRedirects(false)`、`followSslRedirects(false)`、`retryOnConnectionFailure(false)`、无 Cookie/缓存、单 dispatch 网络拦截器。`Operation.abort()`（`:248-256`）在 `cancellationExecutor` 拒绝时**同步**执行 `disconnect()`；超时回调改为 `try { fail } finally { abort }`（`:176-181`），即使终止回调本身失败也保证清理；`InterruptedIOException` 映射为 TIMEOUT（`:369`）。新增 `AiTransportProtocolTest.kt:15-50` 用阻塞写 + 拒绝执行器验证 `disconnect` 仍被调用。真实 TLS 对端的写阻塞行为仍需设备/网络验证。

### M-6 `ContentObserver` 注册异常导致观察者泄漏【复核：已修复】

- **位置**：`app/src/main/kotlin/dev/zeroinput/ime/clipboardguard/ClipboardGuardRuntime.kt:187-190`（`refresh`）、`:254-260`（`detachPlatform`）、`:263-281`（`enqueue` 的异常兜底）。
- **问题描述**：`refresh()` 先调用 `context.contentResolver.registerContentObserver(...)`，成功后才设 `observingDefaultIme = true`。若注册调用本身在「已向系统登记」之后、「返回前」抛 `RuntimeException`（Binder 异常、进程状态变化），标志保持 `false`；`enqueue` 的兜底（`:269-274`）会调用 `detachPlatform()`，但 `detachPlatform()` 因 `observingDefaultIme == false` 不执行 `unregisterContentObserver`。该观察者此后持续接收默认输入法变更并调用 `reconfigure()`，即使防护已关闭或服务已销毁。
- **影响范围**：后台持续的系统设置监听与回调，违反「关闭监听/组件销毁时释放资源」的边界；多次 `reconfigure()` 还会反复递增 `revision`，使待处理的检查/清理请求失效。
- **触发/复现场景**：注入 `ContentResolver` 在 `registerContentObserver` 中先登记再抛异常；或对 `refresh()` 抛异常后断言 `unregisterContentObserver` 仍被调用。属静态可确定的赋值顺序缺陷，实际发生依赖平台异常路径。
- **建议方向**：用 `try/finally` 或先置位再注册的幂等结构；`detachPlatform()` 采用无条件 `runCatching { unregisterContentObserver(...) }` 的幂等注销。
- **复核结论（已修复）**：当前 `ClipboardGuardRuntime.kt:196-199` 先置 `observingDefaultIme = true` 再调用注册；`detachPlatform()`（`:263-271`）在该标志下尝试注销，并在 finally 清理本地所有权；其 `recoverable { ... }.getOrThrow()` 会传播注销失败，原复核所称「吞掉注销异常」不准确；注册/注销被抽成可注入的构造参数（`:28-36`）；`enqueue` 兜底改为 `catch (Throwable)` 并保留取消/中断语义（`:277-287`）。新增 `ClipboardGuardRuntimeTest.kt:16-35` 直接注入「登记成功后抛异常」的注册器，断言 `unregister` 恰被调用一次且状态为 `FAILED`。

### M-7 AI 配置旧格式被静默整体删除，且「有 provider 但未选中」被判为不可用【复核：已修复】

- **位置**：`user-data/src/main/kotlin/dev/zeroinput/userdata/AiConfigurationRepository.kt:98-120`（`load`）、`:102-114`（旧格式分支）、`:260-279`（`validate`）、`:262-263`（选中校验）、`:249`（必填字段）。
- **问题描述**：
  1. `load()` 检测到 `formatVersion in 1..4` 时，直接调用 `store.delete(deleteKey = true)`（`:106`），删除整个加密配置文件**及其专用 Keystore 密钥**，然后返回默认 `AiConfiguration()`。这一路径没有用户确认、没有迁移、没有可判定提示；删除发生在「读取」调用里，任何组件（包括仅想显示 AI 开关状态的 UI）触发 `read()` 都会造成不可逆的配置丢失。此分支只捕获 `Exception`，若删除抛 `Error` 会被外层 `:117` 转成泛化的「配置无效」。
  2. `validate()` 的条件 `providers.isEmpty() && selectedProviderId == null || providers.any { it.id == selectedProviderId }`（`:262-263`）意味着：**providers 非空但 `selectedProviderId == null` 时校验必然失败**。`readConfiguration` 允许 `selectedProviderId` 为 `null`（`:240, :256`），随后 `load()` 用 `also(::validate)` 校验，一旦磁盘上出现这种组合，`load()` 抛 `IllegalStateException("AI configuration is invalid")`，整份配置（包括全部 endpoint 与模型列表）不可读，且 UI 无法自行修复。
- **影响范围**：升级或数据异常后，用户配置的所有 AI provider 可能无声消失；或因为一个可为空的选中字段导致 AI 设置整体不可用。二者都影响数据完整性而非仅体验。
- **触发/复现场景**：构造 `format=4` 的加密内容调用 `read()`；或构造含 1 个 provider、`selectedProviderId=null` 的合法 JSON 调用 `read()`。仓库已有单元测试基础设施，可直接加负向用例。
- **建议方向**：把格式淘汰改为显式、一次性的用户可见操作；放宽「未选中」为合法状态（`activeProvider()` 已返回 null）；对校验失败保留可读的最小配置并给出可恢复路径。
- **复核结论（确认子项已修复，第 1 子项撤回）**：第 2 子项已修复——`AiConfigurationRepository.kt:264-280` 的 `validate` 改为 `selectedProviderId == null || providers.any { it.id == selectedProviderId }`，未选中状态可读可编辑且 `activeProvider()`/`activeKey()` 为空，未知选中 ID 与非法能力仍被拒；`load()` 补 `CancellationException`/`InterruptedException` 重抛（`:114-121`）；新增 `AiRepositoryTest.kt:24-34` 覆盖该组合。**第 1 子项复核不成立**：`docs/adr/0024-ai-provider-protocols.md:50-54` 明确记录「The user chose to discard existing Provider configuration rather than migrate it」以及「Formats 1-4 trigger deletion of the AI configuration file and its dedicated key, then return disabled defaults」，`docs/adr/0024:58-59` 说明用户需重新录入并显式启用网络，`AiRepositoryTest` 已有对应覆盖。该行为是 ADR 批准的产品决策而非缺陷，撤回「静默删除」的缺陷判定；仅保留「删除发生在 `read()` 路径、缺少 UI 可见提示」作为可用性观察项，不计入发现。

### M-8 宽泛异常吞噬破坏取消语义与失败可判定性【复核：已修复】

- **位置**（本轮核实的代表性位置，非穷举）：
  - `ime-core/.../InputSessionController.kt:129, 217, 248, 327, 429-439, 631-639, 675-677, 731-757, 764, 818, 839, 931-936`（大量 `runCatching` 与 `catch (Throwable)`）；
  - `ime-core/.../PersonalCandidatePaging.kt:81-84, 158-160`（`runCatching` 包裹存储查询与频率查询，`.getOrDefault(emptyMap())`）；
  - `ime-core/.../WordAssociationSession.kt:57-66, 79`（联想预测与重排）；
  - `app/.../personalization/QueuedPersonalizationStore.kt:507-510`（`notifyChanges` 对每个监听器 `runCatching { listener() }`）；
  - `language-pack/.../LanguagePackInstaller.kt:104, 128, 262, 645`（`setEnabled`/`remove`/`readInstalled` 用 `runCatching { ... }.getOrDefault(false)`）；
  - `engine-rime/.../RimeRuntime.kt:46, 103, 132-181`；
  - `app/.../clipboardguard/ClipboardGuardRuntime.kt:97, 255, 269`。
- **问题描述**：Kotlin 的 `runCatching` 捕获 `Throwable`，会把 `CancellationException`、`InterruptedException`（包装后）、`OutOfMemoryError`、`LinkageError` 一并转成「空结果」或「false」。这与 `AGENTS.md §10.3`「捕获异常应缩小范围并保持取消语义；安全失败采用 fail-closed」冲突。典型后果：
  - 存储查询抛 `CancellationException` 时返回空页，上层把它当作「暂无个人候选」而不是「操作被取消」；
  - `LanguagePackInstaller.remove()` 抛 `Error` 时返回 `false`，部分删除的中间状态无法与「条目不存在」区分；
  - `notifyChanges` 吞掉监听器异常，UI 渲染失败不会暴露；
  - `InputSessionController.learningFailureCount`（`:86`）虽能计数，但同样不区分取消与真实失败。
- **影响范围**：故障被静默降级为「正常空状态」，问题难以诊断；取消信号被吞掉后，过期任务可能继续执行到下一阶段。属于横切质量问题，不对应单一崩溃。
- **触发/复现场景**：注入抛 `CancellationException` 的 `PersonalizationStore.suggestionsFor()`，观察 `suggestionPage()` 返回 `PersonalSuggestionPage()` 而非取消；注入抛 `OutOfMemoryError` 的监听器，观察 `notifyChanges()` 无异常传播。
- **建议方向**：统一改为「捕获 `Exception` + 显式重抛 `CancellationException`」的辅助函数；对安全/删除路径区分「失败」与「不存在」并返回可判定模型。
- **复核结论（已修复）**：新增 4 份模块内 `RecoverableOperation.kt`（`app/ime-core/engine-rime/language-pack`，各 6–15 行），实现统一：只捕获 `Exception`，重抛 `CancellationException` 与 `InterruptedException`（后者恢复中断标志），`Error` 完全不捕获。所有列出的可选工作边界已替换为 `recoverable`；`WordAssociationSession`、`RimeRuntime`、`AsyncCandidateRanker`、`ClipboardGuardRuntime`、`QueuedPersonalizationStore` 补了取消/中断的显式重抛与清理-重抛结构；`RimeRuntime` 还补了初始化失败时的 `finishInitializationLocked` 兜底。新增负向测试：`PersonalCandidatePagingTest.kt:8-20`（取消/AssertionError 不得变成空页）、`QueuedPersonalizationStoreTest.kt:20-35`（取消的预加载可重试、监听器 Error 传播）、`AsyncCandidateRankerTest.kt`、`InputSessionControllerTest.kt`。**保留设计**：普通 `Exception` 仍降级为默认值或 `false`（这正是原建议允许的范围），native 加载 `LinkageError` 与 AI worker 终止错误仍沿用既有策略，本轮未做全库异常语义重写。

---

## 5. Low

### L-1 英语引擎接受任意 Unicode 字母进入组合【复核：已修复】

- **位置**：`engine-english/src/main/kotlin/dev/zeroinput/engine/english/EnglishInputEngine.kt:81-88`（`handleText`），关联 `:20-24`（词库归一化）、`:181-190`（`buildPrefixIndex`）。
- **问题描述**：`if (text.length == 1 && (text[0].isLetter() || text == "'"))` 使用 Kotlin 的 `Char.isLetter()`（Unicode 分类），因此中文、西里尔、带重音字母都会被追加进 `buffer` 并成为 `rawInput`/`composition`；而 `lexiconWords` 与 `prefixIndex` 只含 `[a-z']`。结果是组合持续变长（上限 64）、候选只剩字面输入本身，`collect(typed, EXACT_INPUT_SCORE)` 还把这段非英文文本作为最高分候选。
- **影响范围**：英文键盘下误触或外部输入非 ASCII 字母时，出现无法转换的预编辑串，且 `selectCandidate` 会自动追加空格提交。
- **触发/复现场景**：英文模式下发送 `EngineKey.Character("中")` 或 `("é")`，断言 `snapshot.rawInput` 与候选内容。
- **建议方向**：把判定改为 `text[0] in 'a'..'z' || in 'A'..'Z' || text == "'"`，与词库字符集一致。
- **复核结论（已修复）**：`EnglishInputEngine.kt:81` 改为 ASCII 范围判定；新增 `EnglishInputEngineTest.kt:13-24` 对 `中`/`é`/`я` 断言不进入组合、无候选、按字面提交且不自动补空格。

### L-2 词库导入的尾部字节与 `read()` 返回 0 的处理【复核：已修复】

- **位置**：`engine-dictionary/src/main/kotlin/dev/zeroinput/engine/dictionary/importer/CellDictionaryParser.kt:36-53`（`parse` 主循环）、`:57-59`（`u16`）、`:69-78`（`readBytesLimited`）。
- **问题描述**：
  1. 主循环 `while (data.hasRemaining())` 的第一句是 `val homophones = data.u16()`；`u16()` 只在 `remaining() >= 2` 时通过，否则抛 `IllegalArgumentException("Truncated cell dictionary")`。**但该循环体内其他读取（如 `:341` 的 `data.text(...)`、`:343` 的 `extraLength`）抛出的 `BufferUnderflowException`/`IllegalArgumentException` 都没有统一包装**，与 `readBytesLimited` 的受控失败消息不一致；调用方按「格式错误」还是「I/O 错误」处理会出现分歧。
  2. `readBytesLimited()` 的 `while` 循环没有处理 `InputStream.read()` 返回 `0` 的情形（`InputStream` 契约允许在某些实现下返回 0），此时会进入空转并持续 `output.write(buffer, 0, 0)`，不推进也不报错。
- **影响范围**：畸形/截断的 QQ、Sogou 细胞词库在导入时产生不一致的异常类型；极端情况下某个 ContentProvider 输入流返回 0 会造成导入线程忙等。
- **触发/复现场景**：把合法样本截断 1 字节后调用 `parse`；用自定义 `InputStream` 连续返回 0 调用 `readBytesLimited`。
- **建议方向**：循环内统一用 `u16()`/`text()` 包裹所有读取（或统一 `require` 消息），并在 `read()==0` 时 `continue` 或按协议错误退出。
- **复核结论（确认子项已修复，第 1 子项撤回）**：第 2 子项已修复——`CellDictionaryParser.kt:69-81` 增加 `require(size > 0) { "Cell dictionary stream made no progress" }` 与线程中断检查；新增 `PublicDictionaryParserTest.kt:32-39` 用零进度流 + 1 s 超时断言不再空转。**第 1 子项复核不成立**：审计基线中 `:38-50` 的 `u16()`、`text()`（`:62-67` 已有 `require(size in 2..maximum && ... && size <= remaining())`）、`extraLength`（`:48` 已有 `require`）均为受控读取，异常类型一致；新增 `PublicDictionaryParserTest.kt:9-30` 对合法样本截断 1..13 字节逐一验证均抛 `IllegalArgumentException`。原「其他读取未统一包装」的描述与源码不符，撤回。

### L-3 阶段 6 音频采集的非空断言与缓冲清理【复核：已修复】

- **位置**：`app/src/main/kotlin/dev/zeroinput/ime/ai/device/DeviceStage6SensorCapture.kt:33-37`、`:55-60`、`:66-72`。
- **问题描述**：`wav` 声明为 `ByteArray?`，在 `:55` 赋值后立即用 `wav!!.size` 与 `wav!!.copyOf()`（`:56, :60`）。当前控制流下赋值成功才到达这两行，因此不会实际 NPE，但违反 `AGENTS.md §10.3`「避免 `!!`」，且一旦将来在赋值与读取之间插入分支就会变成崩溃。另 `:33` 分配 32 KB `pcm` 后，`:37` 的 `AudioRecord` 构造失败分支直接 `return unavailable(...)`，此时尚未进入 `try/finally`，`pcm` 未被 `fill(0)` 清零（该缓冲为静音/未初始化数据，非用户秘密，但不符合「采集缓冲一律清零」的既有约定）。
- **影响范围**：健壮性与代码规范；不影响权限审批边界。
- **触发/复现场景**：静态代码检查即可定位；`AudioRecord` 构造失败可用权限缺失设备复现。
- **建议方向**：用局部 `val encoded = wav(pcm, size, rate)` 直接替代可空变量；把 `pcm` 分配移入 `try` 或在提前返回前清零。
- **复核结论（已修复）**：`DeviceStage6SensorCapture.kt:33-60` 把 `pcm` 分配移到 `AudioRecord` 构造成功之后，构造失败路径不再分配缓冲；编码结果改为局部 `val encoded`，两个 `!!` 已移除，既有 `finally` 清零保留。

### L-4 成对符号提交失败时返回值与回调不一致【复核：已修复】

- **位置**：`app/src/main/kotlin/dev/zeroinput/ime/input/AndroidEditorConnection.kt:75-98`（`commitPairedText`）。
- **问题描述**：`:81` 的 `connection.commitText(text, 1)` 成功后立即 `onCommitted(text)`（`:88`）。随后 `:89-95` 为把光标放到成对符号之间而调用 `setSelection`；若该调用失败，方法执行 `invalidateContext()` 并 `return false`，但**文本已经写入编辑器，且 `onCommitted(text)` 已经通知过上层**，也没有补发 `onCommitted(null)`。调用方拿到 `false` 会认为提交未发生，而联想/模型上下文却按「已提交」处理。
- **影响范围**：光标定位失败（部分编辑器不支持 `setSelection`）时状态分裂；`InputSessionController.commitPaired()`（`:697-708`）当前忽略返回值，因此不产生重复输入，但上层诊断与联想上下文会不一致。
- **触发/复现场景**：注入 `commitText` 返回 true、`setSelection` 返回 false 的 `InputConnection`，断言返回值与 `onCommitted` 调用序列。
- **建议方向**：把「文本提交成功」与「光标调整」解耦，文本成功后返回 true 并把光标失败作为独立的可忽略状态；或失败时补发一致性回调。
- **复核结论（已修复）**：`AndroidEditorConnection.kt:89-98` 在 `setSelection` 失败时只 `invalidateContext()`（失效上下文）并继续 `return true`，不再与 `onCommitted(text)` 分裂；新增 `EditorPairedTextTest.kt:13-26` 用 `InputConnectionWrapper` 断言返回 `true`、只写一次、回调为 `["()"]` 且上下文被失效。

### L-5 语言包 staging 目录清理的边界【复核：撤回（原判断不成立）】

- **位置**：`language-pack/src/main/kotlin/dev/zeroinput/languagepack/LanguagePackInstaller.kt:248-252`（`createStagingDirectory`）、`:77-83`（`recoverInterruptedInstallations`）。
- **问题描述**：目录名由 `"${manifest.id}-${System.nanoTime()}"` 生成，而 `System.nanoTime()` 允许为负值；清理用的正则 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}--?[0-9]+` 依赖连字符数量与回溯来匹配正负值，且要求 id 部分不含超过 63 字符（清单已限制，OK）。当 id 以数字/连字符结尾时，`[A-Za-z0-9._-]{0,63}` 可能吞掉分隔连字符，使负号 `-` 无法被 `--?` 匹配，残留目录不会被清理。残留 staging 目录会在每次 `recoverInterruptedInstallations()` 中被反复遍历，且占用 `noBackupFilesDir`。
- **影响范围**：中断安装后的临时目录残留（低概率，依赖 nanoTime 符号与 id 形态）。
- **触发/复现场景**：用固定负 `nanoTime` 与形如 `pack2` 的 id 构造目录名，调用 `recoverInterruptedInstallations()` 检查是否删除。
- **建议方向**：使用不含符号的单调计数或固定前缀 + 绝对值，清理时按前缀枚举并校验目录创建时间，不依赖正则形态。
- **复核结论（撤回）**：原描述对正则的解读有误。`--?` 明确允许 **1 或 2 个连字符**，正是为分隔连字符 + 负 `nanoTime` 符号设计的；`[A-Za-z0-9._-]{0,63}` 的贪婪匹配在失败时会回溯到可行切分，id 以数字或连字符结尾不影响结果。本轮用 Python 等价正则（`fullmatch`，与 Kotlin `String.matches()` 同为全串匹配）实测 10 组目录名，包含 `pack2--9223372036854775808`（`Long.MIN_VALUE`）、`pack2-123`、`id-with-hyphen--5`、64 字符 id，**全部匹配**。新增平台测试 `LanguagePackJsonPlatformTest.kt:156` 也验证了带符号 staging 目录被清理且无关目录保留。当前实现未改动，不构成缺陷。

### L-6 JNI 字符串转换对空值与畸形 UTF-8 的处理【复核：已修复】

- **位置**：`engine-rime/src/main/cpp/jni_strings.h:68-84`（`ToJString`）、`:53-66`（`DecodeUtf8CodePoint`）；调用点 `zero_rime_jni.cpp:291, 293, 298, 300-305`。
- **问题描述**：空字符串走 `env->NewString(nullptr, 0)`（`:69`），行为依赖具体 JNI 实现对「空指针 + 零长度」的容忍；更稳妥的写法是用长度为 0 的有效数组。`DecodeUtf8CodePoint` 对 `0x80..0xBF` 的孤立续字节按 `continuation_count = 1` 解码出一个非法小码点，且未拒绝过长编码、代理区码点与 `0x10FFFF` 以上之外的其他保留值；`ToJString` 会把这些码点原样塞进 Java 字符串。
- **影响范围**：`nativeReadUpdate` 在空输入/空提交文本时是常见路径；畸形字节只会造成显示文本异常，不构成内存安全问题。需 CheckJNI/设备验证。
- **触发/复现场景**：以空 `rawInput` 调用 `nativeReadUpdate`；或让 librime 返回含孤立续字节的候选文本。
- **建议方向**：对空串使用零长度非 null 缓冲；解码时校验首字节范围、续字节数量与码点合法性，非法一律替换为 `U+FFFD`。
- **复核结论（已修复）**：`jni_strings.h:53-84` 现在对首字节限定 `0xc2..0xf4`、校验续字节、最小码点（0x80/0x800/0x10000）、代理区与 `0x10FFFF` 上限，非法一律 `0xfffd`；续字节无效时不推进游标，从而保留紧随其后的合法 ASCII 字节；空串/`nullptr` 改用 `static const jchar empty` 的有效地址（`:73-75`）。`jni_failure_test.cpp:33-38, 75-99` 增加 `NewString` 非空断言、空串/nullptr 调用、合法多字节与 7 组畸形序列（孤立续字节、过长编码、代理区、越界、截断）以及「坏字节后跟 ASCII」用例。真实 Android CheckJNI 行为仍属设备验证范围。

### L-7 候选窗口按文本跨页去重会吞掉同形异音候选【复核：已修复】

- **位置**：`ime-core/src/main/kotlin/dev/zeroinput/ime/core/CandidateWindow.kt:45-54`（`snapshot`），关联 `:51`（id 改写）、`:63-70`（`select`）。
- **问题描述**：浏览多页时，`seen` 集合以 `candidate.text` 为键（`:43, :49`），不同页上文本相同但 `id`/读音不同的候选（多音字、不同编码路径）只保留首次出现的那个；`routes` 相应只为该文本建立一条映射。用户因此无法选中后面那条的特定读音/编辑路径。
- **影响范围**：候选浏览（最多 6 页）时的选词正确性；不影响首屏。
- **触发/复现场景**：构造两页候选，第 0 页与第 2 页各有一个 `text="行"` 但 `id` 与 `pinyinMatch` 不同的候选，调用 `changePage` 后断言 `snapshot()` 的候选数量。
- **建议方向**：以 `(page, index, id)` 或 `id` 去重，仅在明确是同一候选重复时合并。
- **复核结论（已修复）**：`CandidateWindow.kt:45-54` 移除 `seen`，跨页 id 改为 `page:$page:$index:${candidate.id}`，保证同形异音候选各有独立路由；`PersonalCandidatePaging.kt:134-137` 改为保留全部 native 条目、仅对 `personal:` 前缀条目按 text 合并，最后按 `id` 去重；`PersonalCandidateRanking.kt:10-18` 同样按 id 保留 native 身份，personal 只在文本不在 native 时加入。新增 `CandidateWindowTest.kt:8-43` 构造 `xing`/`hang` 两个 `text="行"` 的候选，断言两条路由与读音均保留。

### L-8 加密写入的入参清零与重复代次校验【复核：撤回（保留设计）】

- **位置**：`security/src/main/kotlin/dev/zeroinput/security/EncryptedFileStore.kt:43-65`（`writeIfActive`）、`:51`（`beforeCommit`）、`:61-64`（`finally`）；`user-data/src/main/kotlin/dev/zeroinput/userdata/PrivateDraftEntries.kt:23-38`（`add`）。
- **问题描述**：
  1. `writeIfActive` 在 `finally` 中无条件 `plaintext.fill(0)`（`:62`），包括 `beforeCommit()` 代次校验失败、`beforeWrite`/加密失败、以及 `AtomicFile` 提交失败的所有分支。这在接口 `EncryptedStore.write()` 上没有明确契约声明；若调用方按「失败可重试」语义复用同一个数组，第二次写入的内容是全零字节，会造成数据损坏。**当前所有调用方**（`AiConfigurationRepository.write`、`SecureClipboardVault.persist`、`PrivateDraftEntries.add`）都传入临时数组且自行清零，因此现存路径不受影响，属于接口陷阱。
  2. `PrivateDraftEntries.add()` 在 `:33` 调用一次 `beforeWrite()`，又在 `:34` 把同一个 lambda 作为 `beforeCommit` 传给 `writeIfActive`。当前 lambda 是 `SecureClipboardVault.addPrivateDraft` 的纯校验（`:140-142`），无副作用；但语义上一次写入执行了两次代次校验，且第一次离真正提交点还隔着 Keystore 加密与 `startWrite()`。
- **影响范围**：可维护性与未来调用方的正确性；不改变当前行为。
- **触发/复现场景**：注入在 `beforeCommit` 抛 `CancellationException` 的场景，检查传入 `writeIfActive` 的数组是否已被清零。
- **建议方向**：在 `EncryptedStore` 契约上明确「写入消费并清零入参」，或只在成功提交后清零；`PrivateDraftEntries.add` 去掉 `:33` 的重复校验。
- **复核结论（撤回）**：第 1 子项与源码不符——`security/.../EncryptedStore.kt:3` 在**审计基线**就已写明「Callers clear read buffers; writes consume and clear their input」，契约已存在；本次仅在 `EncryptedFileStore.kt:42-43` 与 `EncryptedStore.kt:6` 补了更明确的 KDoc（「Takes ownership and clears [plaintext] on success, failure and cancellation; retries need fresh input」），行为未变。第 2 子项属有意设计：两次校验分别覆盖「Keystore 加密前」与「发布/提交前」两个不同竞态窗口，`PrivateDraftEntries.kt:30-35` 新增注释说明；去掉其中一次会扩大代次竞争。因此两项均不构成缺陷，撤回该发现，仅保留为设计说明。

### L-9 语言包校验的内存放大【复核：部分修复，见 §10.3 R-1】

- **位置**：`language-pack/src/main/kotlin/dev/zeroinput/languagepack/LanguagePackEngine.kt:147-165`（`LanguagePackDictionaryLoader.load`）、`:168-177`（`readVerified`）。
- **问题描述**：`readVerified` 先用 `LanguagePackManifestReader.read()` 把最多 16 MiB 的文件读成 `String`（UTF-16，约 32 MiB char 数据），再 `text.toByteArray(UTF_8)` 生成第二份 16 MiB 副本用于 SHA-256（`:171-174`），同时 `text` 又通过 `lineSequence()` 被上层继续持有。单个文件峰值约为原始大小的 3 倍；`MAX_TOTAL_TEXT_BYTES` 为 32 MiB（`:145`），最坏情况下瞬时堆占用接近 100 MiB。
- **影响范围**：低端设备加载大语言包时的 GC 压力或 OOM；`LanguagePackRegistry.refresh()`（`LanguagePackRegistry.kt:13-24`）还会对每个启用的包依次执行该流程并调用 `isAvailable()`（`LanguagePackEngine.kt:38`，触发 `dictionary` 的 lazy 全量解析），**调用线程必须是后台 worker**，否则会阻塞 UI/IME 主线程（本轮未逐层确认 `AppGraph.refreshLanguagePacks()`（`AppGraph.kt:419-435`）的所有调用线程）。
- **触发/复现场景**：安装一个含 16 MiB `.txt` 词典的语言包并在低端设备上启用。
- **建议方向**：改为流式读取 + 增量摘要，避免 `String` 与 `byte[]` 双份驻留；为 `refreshLanguagePacks()` 增加线程断言或显式调度到 engine worker。
- **复核结论（已修复）**：TXT/YAML 保留增量 SHA-256、有界字节读取、严格 UTF-8 与有界行解析；JSON 进一步改用 `LanguagePackJsonReader` 事件解析及 `LanguagePackDictionaryJson` 有界折叠，不再保存整文件 String 或 JSONObject/JSONArray 树。独立实验用 400,000 个重复合法条目构成 14,000,001-byte 文件，原实现于 `-Xmx128m` 稳定 OOM，修复后同一探针输出 `AVAILABLE=true BYTES=14000001`。新增边界：最多 16 层容器、4,096 字符 token、每对象 50,000 个键、嵌套同时保留最多 100,000 个键，沿用 50,000 输出词条与原文件/总字节上限；达到输出预算后继续校验语法、重复键、UTF-8、大小和哈希。共享清单语法检查，保留原记录优先级和映射/数组格式；相关整包负向测试改用真实哈希，确保真正走到解析路径。`refreshLanguagePacks()` 现有调用仍位于初始化/设置 worker。设备实际堆峰值与耗时未测，局部固定堆实验不代表所有设备上限，详见 §9。**第三次独立核查改判为部分修复**：该红绿实验只覆盖「单层重复记录」形状；新解析器的 50,000 条上限按容器帧生效而非全局，合法嵌套 JSON 在相同 `-Xmx128m` 条件下仍会 OOM，见 §10.3 R-1。

### L-10 安全剪贴板删除先消费授权，失败后需重新认证【复核：撤回（既定安全设计）】

- **位置**：`user-data/src/main/kotlin/dev/zeroinput/userdata/SecureClipboardVault.kt:151-170`（`remove`），重点 `:155`（`grant.consume()`）、`:161-168`（删除与代次 CAS）。
- **问题描述**：`remove()` 在 `:155` 立即消费一次性 `AuthenticationGrant`，之后才执行 `load()`、`removeAll`、`generation.compareAndSet` 与 `commit()`。若其中任一步抛异常（代次变化抛 `CancellationException`、加密写入失败、索引修复失败），条目仍在库中，而授权已被消费，用户必须重新做一次系统认证才能重试。
- **影响范围**：可用性；不会造成越权或明文泄露。
- **触发/复现场景**：在 `commit()` 注入写入失败，断言 `grant.consume()` 已被调用而条目仍存在。
- **建议方向**：把授权消费推迟到提交前最后一次校验之后，或在失败路径明确返回「授权已失效」的可判定状态。
- **复核结论（撤回）**：`remove()` 需要 `load()` **解密现有条目内容**才能完成删除，该读取本身受一次性授权保护；把 `grant.consume()` 后移到 `load()` 之后会让明文读取发生在授权边界之外，削弱已批准的「先授权、后解密」模型。原「建议后移」不成立，该顺序是有意的安全设计，代码未改动。失败后需重新认证是该边界的可用性代价，保留为观察项（可考虑在 UI 明确提示「授权已失效，请重新认证」），不计入缺陷。

### L-11 `AsyncCandidateRanker.drain` 的 `draining` 标志缺少通用兜底【复核：已修复】

- **位置**：`ime-core/src/main/kotlin/dev/zeroinput/ime/core/AsyncCandidateRanker.kt:93-108`（`drain`）、`:110-133`（`drainRequests`）、`:79-91`（`scheduleLocked`）。
- **问题描述**：`drain()` 只对 `Error` 做恢复（设置 `failed=true`、`cancelLocked()`、`draining=false` 后重抛）；若 `drainRequests()` 抛出未被 `score()`/`releaseScorer()` 覆盖的 `RuntimeException`（例如未来在循环内新增的回调或 `synchronized` 块内逻辑），`draining` 将永久保持 `true`，`scheduleLocked()` 在 `:80` 直接返回，此后所有 `request()` 都只入队不调度，排序功能静默停摆。
- **影响范围**：模型重排功能（可选、默认关闭）失效，不影响基础输入；属防御性缺口，本轮未构造出可稳定触发的现有路径。
- **触发/复现场景**：注入在 `drainRequests()` 的同步块内抛 `RuntimeException` 的 `createScorer`/回调，观察后续 `request()` 是否还能投递。
- **建议方向**：在 `drain()` 增加 `finally { if (worker 异常退出) synchronized(lock) { draining = false } }` 兜底，并对重复投递做幂等处理。
- **复核结论（已修复）**：`AsyncCandidateRanker.kt:94-109` 的 `drain()` 改为 `catch (Throwable)`，在 `finally` 中释放 scorer、`cancelLocked()`、复位 `draining`，随后重抛；`scheduleLocked()`（`:79-91`）同样覆盖 `Throwable` 并对取消/非 RuntimeException 保持传播；`score()`（`:151-158`）显式重抛取消/中断；`releaseScorer` 用 `recoverable`。新增 `AsyncCandidateRankerTest.kt:11-31` 注入时钟失败，断言输入缓冲被清零、异常传播，且 `resourcesChanged()` 后新请求可正常投递。

---

## 6. 本轮独立核查后判定不成立 / 未纳入的疑点

以下均为审计阶段从当时源码**重新验证**的结论，用于说明覆盖深度，避免把已修复或属设计取舍的内容误报为缺陷；复核阶段新增的撤回项见 §8。

| 疑点 | 本轮核查结论 |
| --- | --- |
| JNI 入口缺少 C++ 异常边界 | **不成立**。`zero_rime_jni.cpp` 的 17 个 `JNIEXPORT` 入口全部使用函数级 `try { ... } catch (...) { zero_jni::TranslateException(env); }`，`jni_boundary.h:17-25` 区分 `bad_alloc` 与其他异常并转 Java 异常。 |
| `ToStringArray` 在 JNI 分配失败后继续调用 | **不成立**。`jni_strings.h:86-103` 对 `FindClass`、`NewObjectArray`、`ToJString`、`SetObjectArrayElement` 均检查结果与 `ExceptionCheck()` 并立即返回。 |
| 网络门禁漏检 `ServerSocket` / `SocketChannel` / DNS API | **不成立**。审计时 `build.gradle.kts:32` 的正则已包含 `(?:Server)?Socket`、`Asynchronous?SocketChannel`、`DatagramChannel` 与 `getByName|getAllByName|...`。缺口在扫描范围而非正则（见 M-4）；复核时又补了 `InetSocketAddress`、`NetworkChannel`。 |
| AI 凭据（明文 apiKey）长期留在应用级缓存 | **不成立**。`AiConfigurationState.publish()`（`AiConfigurationState.kt:29-35`）一律 `configuration?.withoutCredentials()`；`AppGraph.resolveAiCredentials()`（`AppGraph.kt:215-223`）只在传输 worker 上按提交时快照注入当前选中 provider 的凭据。 |
| 语言包单行字段不受限导致内存放大 | **不成立**。`LanguagePackDictionaryLoader` 现有 `MAX_LINE_CHARS = 4096`、`MAX_LINE_FIELDS = 16`，`load()` 在 `parseLine` 之前对每行执行 `isBoundedLine()`。 |
| AI 适配器允许重定向 | **不成立**。`OpenAiCompatibleProvider.kt:281` 显式 `http.instanceFollowRedirects = false`；`AiRemoteHttpsTransport.kt:46` 使用 `Proxy.NO_PROXY`。复核后两者均为 OkHttp 且显式禁用重定向/重试。 |
| 认证导航时限实现与文档不一致 | **不成立（当前实现一致）**。`AuthenticationBroker.kt:91` 请求期限 30 s，`SecurePasteConsent.kt:15, :26` 初始/授权后各 30 s，`SecurePasteCoordinator.kt:50, :57` 同样 30 s。 |
| Android 8.x 剪贴板时间戳时钟基准混用 | **不成立**。经本机 SDK `sources/android-37.0/android/content/ClipDescription.java:256-277` 核对，`getTimestamp()/setTimestamp()` 明确使用 `System.currentTimeMillis()` 基准，与 `AndroidSystemClipboard.kt:28-32` 的写入窗口一致；`SystemClock.elapsedRealtime()` 仅用于 `OwnEmptyClipboardStamp` 的 1 s 单调到期判断，二者用途不同、不构成混用。 |
| Manifest 存在未经批准的导出组件 | **不成立**。逐项核对 `app/src/main/AndroidManifest.xml`（第 54-272 行）与 `build.gradle.kts:106-116` 白名单：9 个导出组件均在批准表内，`allowBackup="false"`、`usesCleartextTraffic="false"`、备份规则引用正确；`AiDisabledRecognitionService` 为 `exported="false"`。 |
| `Release` 构建可调试 | 审计阶段未验证。`build.gradle.kts:103` 的 `manifestBoundaryViolations` 在 release 变体断言 `debuggable` 为空或 false；复核阶段已有 Debug/Release 合并清单检查产物（见 §7），但未由本轮独立执行。 |

## 7. 验证记录与限制

- **历史记录**：原审计没有执行动态验证；首次静态复核尝试 `./gradlew.bat privacyCheck :ime-core:test :app:testDebugUnitTest --no-parallel --max-workers=4`，因 `E:/gradle-cache/caches/journal-1/journal-1.lock` 拒绝访问失败。该失败不是通过证据，也不是代码缺陷。首次引用的 2,114 次通过、50 个设备用例及旧 APK 哈希只对应当时源码；详见 [上一轮验证记录](2026-11-18-followup-verification.md)。
- **最终再核验**：独立复跑定向测试、全量 JVM 测试、隐私门禁、双变体 Lint、真实 Rime 三 ABI 构建及相关设备检查。具体命令、通过数量、产物与实验结果见 §9；静态复核时的锁失败不再表示当前验证仍受阻。
- **第三次独立核查**：`./gradlew.bat privacyCheck :language-pack:testDebugUnitTest :ime-core:testDebugUnitTest --no-parallel --max-workers=4` 独立退出 0（`BUILD SUCCESSFUL in 45s`，10 executed / 1 from cache / 103 up-to-date）；重新统计磁盘 396 个测试 XML、重算 6 个 APK 大小与主/测试 APK 哈希、核对 Lint 报告与设备计数；新增嵌套 JSON 固定堆对照实验发现 R-1。详见 §10。
- **保留行为**：普通引擎异常的受控输入降级有现有契约测试；词典编辑等待时 Space 保留组合且不缓存按键；AI 旧格式重置已有 ADR 0024 授权；安全剪贴板必须先消费一次性授权再解密，失败后重新认证；私密草稿两次校验覆盖不同竞态窗口。这些不再作为未修复项，也不为关闭报告而削弱既有边界。
- **运行时限制**：尚未验证真实恶意 TLS 对端写阻塞、实体 ARM/OEM、Android 8 时序、传感器权限异常的全部平台路径，以及 Android 设备实际堆峰值/耗时。API 37 模拟器和固定 128 MiB JVM 堆实验不能替代这些证据。可选真实 provider 测试没有临时凭据，保持跳过。
- **静态规则限制**：构建门禁检查仓库自有实现及精确批准路径；固定来源/哈希的第三方按选中构建目标审核。文本规则不是动态信息流分析，不能据此声称已防住任意混淆或未来依赖中的能力变化。
- **范围**：第三次核查后仍有 1 项确认待修复（§10.3 R-1，L-9 残留）；未声称全库异常处理已穷举、所有设备已覆盖或 Release 已可发布。Release 未配置正式签名，无密钥生成、提交或发布。

## 8. 首次静态复核历史（2026-11-18 12:16–12:30）

> 本节保留当时的基线、状态与残留判断；其中「部分修复」「残留缺口」「未独立执行」已由最终再核验补充或纠正，不作为当前结论。最新处置见 §2 与 §9。本节行号对应首次复核时源码。

### 8.1 基线与改动范围

- 审计时源码 = `HEAD` `3632434` 的产品源码；报告于 11:18 生成，修复集中在 11:33–11:53，工作区复核记录 `docs/audits/2026-11-18-followup-verification.md` 于 12:04 写入。本轮复核**未采信该记录的结论**，而是从 `git diff HEAD` 与当前源码重新取证。
- 改动规模：25 个主源码文件、14 个测试文件修改；新增 6 个源码文件（4 份跨模块 `RecoverableOperation.kt`、`ClipboardGuardRuntimeTest.kt`、`EditorPairedTextTest.kt`）。另有多份文档（`docs/architecture.md`、`docs/threat-model.md`、`README.md`、`SECURITY.md` 等）与本报告变更，不属于产品代码。
- 本轮未修改任何产品源码、清单或构建脚本；仅重写本报告。

### 8.2 逐项处置与证据

| 编号 | 状态 | 修复后关键证据（当前行号） | 回归测试 / 独立验证 |
| --- | --- | --- | --- |
| H-1 | 已修复 | `QueuedPersonalizationStore.kt:274-277, 355-362, 310, 320-321`：拒绝时抛异常、显式重试可恢复 | `QueuedPersonalizationStoreTest.kt:248-265` |
| M-1 | 部分修复 | `InputSessionController.kt:222`、`RecoverableOperation.kt:6-21`、`publish :833-836`、`recoverFromEngineFailure :956-966` | `InputSessionControllerTest.kt:28, 51, 76`；保留普通引擎异常的原文提交降级 |
| M-2 | 已修复 | `InputSessionController.kt:226-243`；`ZeroInputService.kt:2011-2016, 2042-2048` 组合中延迟切换 | `InputSessionControllerTest.kt:51, :237`；原复现场景已更正 |
| M-3 | 已修复 | `InputSessionController.kt:171-188`、`commitUnconsumedKey :854-869` | `DictionaryCommitTest.kt:16-24` |
| M-4 | 部分修复 | `build.gradle.kts:31-46, 53-83, 513-548, 664-676` | 门禁负向自测 `:463-483`；残留 CMake/`tools`/`third_party` |
| M-5 | 已修复 | `OpenAiCompatibleProvider.kt:176-181, 248-256, 270-325, 369`；`AiTransportExchange.kt:6-10` | `AiTransportProtocolTest.kt:15-50`（阻塞写 + 拒绝执行器） |
| M-6 | 已修复 | `ClipboardGuardRuntime.kt:28-36, 196-199, 263-271, 277-287` | `ClipboardGuardRuntimeTest.kt:16-35` |
| M-7 | 部分修复 | `AiConfigurationRepository.kt:114-121, 264-280`；`docs/adr/0024:50-59` | `AiRepositoryTest.kt:24-34`；旧格式删除改判为 ADR 批准设计 |
| M-8 | 已修复 | 4 份 `RecoverableOperation.kt`；`RimeRuntime`、`WordAssociationSession`、`PersonalCandidatePaging`、`LanguagePackInstaller/Registry/Engine` 替换 | `PersonalCandidatePagingTest.kt:8-20`、`QueuedPersonalizationStoreTest.kt:20-35` 等 |
| L-1 | 已修复 | `EnglishInputEngine.kt:81` | `EnglishInputEngineTest.kt:13-24` |
| L-2 | 部分修复 | `CellDictionaryParser.kt:38-50, 57-81` | `PublicDictionaryParserTest.kt:9-39`；截断子项改判不成立 |
| L-3 | 已修复 | `DeviceStage6SensorCapture.kt:33-60` | 静态核对；设备权限路径仍属运行时验证 |
| L-4 | 已修复 | `AndroidEditorConnection.kt:89-98` | `EditorPairedTextTest.kt:13-26` |
| L-5 | 复核撤回 | `LanguagePackInstaller.kt:79, 248-252` 未改动 | Python 等价正则 10 组用例全部 `fullmatch=True`；`LanguagePackJsonPlatformTest.kt:113-128` |
| L-6 | 已修复 | `jni_strings.h:53-84, 73-75` | `jni_failure_test.cpp:33-38, 75-99` |
| L-7 | 已修复 | `CandidateWindow.kt:45-54`、`PersonalCandidatePaging.kt:134-137`、`PersonalCandidateRanking.kt:10-18` | `CandidateWindowTest.kt:8-43` |
| L-8 | 复核撤回 | 基线 `EncryptedStore.kt:3` 已有契约；`EncryptedFileStore.kt:42-43`、`PrivateDraftEntries.kt:30-35` 仅补注释 | 契约文档核对；两次校验覆盖不同竞态窗口 |
| L-9 | 部分修复 | `LanguagePackEngine.kt:154-210` | 平台测试覆盖语言包解析；JSON 分支仍全量 `readText()`，堆峰值未测 |
| L-10 | 复核撤回 | `SecureClipboardVault.kt:151-170` 未改动 | 授权先于解密属既定安全边界，原「后移」建议不成立 |
| L-11 | 已修复 | `AsyncCandidateRanker.kt:79-109, 151-158` | `AsyncCandidateRankerTest.kt:11-31` |

### 8.3 结论

- **无 Critical / High 残留**；唯一的 High（H-1）已修复，设置主路径原本就走 `clearAndAwait()`，现异步接口路径也已具备可判定失败与显式重试。
- **12 项完全修复、5 项部分修复、3 项复核撤回**。撤回项分别是：L-5（正则解读错误）、L-8（既有契约已存在 + 有意双重校验）、L-10（既定安全设计）；另有 M-7 的旧格式删除子项、L-2 的截断包装子项经复核改判为不成立，均已在对应条目中显式标注。
- **残留风险集中在 6 处**：M-4 门禁对 CMake/`tools`/`third_party` 的覆盖缺口；M-1 普通引擎异常仍提交组合原文并整会话降级；M-3 待处理编辑时空格被吞；L-9 JSON 仍全量解析；M-7/L-10 的可用性提示；H-1 新契约（`clear()` 可抛异常）需调用方遵循。全部为 Medium 以下的设计取舍或防护纵深问题，无数据泄露、越权或未删除数据路径。
- **验证边界**：本轮为静态复核（含测试代码与既有验证产物核对），独立 Gradle 复跑因环境锁失败；动态构建/设备结论引用的是工作区既有日志与结果文件，已在 §7 明确区分。

## 9. 最终再核验（2026-11-18）

> 本节记录 12:42–13:10 的当时结论。13:11 之后的第三次独立核查见 §10；其中 L-9 因新发现 R-1 改判为部分修复，本节「0 项确认待修复」与 §7 的旧表述不再作为当前结论。历史记录保留以便追溯。

### 9.1 当前处置结论

再次逐项检查更新后的报告与对应实现，确认仍需补修的实现缺口为 M-4 与 L-9；已先完成修复，并补充 H-1 的公开契约。当前 **17 项确认问题已修复、3 项撤回、0 项确认待修复**。这表示报告所列缺陷闭环，不代表全库/全部平台均无缺陷。

| 首次复核残留 | 最终处理与判定依据 |
| --- | --- |
| M-4 构建工具扫描遗漏 | 新增自有 CMake/主机脚本/catalog 的分类与独立规则；门禁自测、真实临时违规文件均验证能拒绝。固定供应商源码按版本/哈希和选中构建目标审核；不把未编译示例列为运行时联网。 |
| L-9 JSON 整树内存放大 | 先用同一合法重复词条文件复现 OOM，再改为严格事件流解析和有界词条折叠；保持整包失败关闭、完整语法及摘要校验。 |
| H-1 clear 契约提示 | 接口 KDoc 明确接受与持久完成的区别、拒绝/关闭异常和显式重试。设置页继续使用持久完成 API。 |
| M-1 普通引擎失败仍降级 | 任意 Error/取消触发错误提交的原缺陷已修复；真实普通引擎异常保留已选片段和当前输入的降级有 `PartialCompositionFallbackTest`，保留输入可用性设计。 |
| M-3 等待中 Space | 保留组合、不缓存延迟按键，刷新后再次选择；README 与 `DictionaryCommitTest` 一致，不再列为残留缺陷。 |
| M-7 / L-2 子项改判 | 未选中 provider 和零进度流均已修复；旧格式重置已有 ADR 0024 授权，截断读取已有受控边界，因此另一子项撤回。 |
| L-5 / L-8 / L-10 | 原正则判断不成立、写入消费契约与双重代次校验已存在、授权必须先于解密，三项整体撤回；未为关闭报告削弱安全边界。 |

### 9.2 实现与负向实验

- JSON 使用 `LanguagePackJsonReader.kt`、`LanguagePackDictionaryJson.kt` 和共享 `PackDictionaryEntry.kt`，不再构建全文件文本/对象树；清单复用语法 reader。新增 4 项 JVM 回归覆盖已有数据形状、14 MB 增量重复输入、输出预算后的非法尾部/重复键、token/对象键限额。部分旧负向用例原先可能提前被假哈希拦截，现改用真实哈希，确保会检查解析行为。
- JSON 上限为 16 层容器、每 token 4,096 字符、每对象 50,000 个键、嵌套同时保留 100,000 个键；沿用每文件 16 MiB、总文本 32 MiB、50,000 输出词条。达到输出额度后仍继续严格语法、UTF-8、字节数及 SHA-256 校验。该变化收紧导入资源预算，不改变持久化格式。
- 固定堆红绿探针：400,000 个重复合法记录，UTF-8 文件 **14,000,001 bytes**，相同 `AuditJsonHeapProbe` 与 `-Xmx128m`。修复前输出 `REPRODUCED_JSON_OOM`、退出 2；修复后输出 `AVAILABLE=true BYTES=14000001`、退出 0。证据：`.tmp-evidence/audit-rereview-json-red.log`、`audit-rereview-json-green.log` 和探针源码。未测量 Android 实际峰值/延迟，不把此实验等同全设备内存保证。
- 门禁实测：分别向 `tools/`、`engine-rime/src/main/cpp/`、`gradle/` 放入违规 `.ps1/.cmake/.toml` 文件，`privacyCheck` 同时列出三项并按预期失败；随后删除全部临时文件，定向及全量门禁通过。证据：`.tmp-evidence/audit-rereview-gate-negative.log`。这是预期失败实验，不与构建成功混记。

### 9.3 独立构建、测试与产物

```powershell
./gradlew.bat :language-pack:testDebugUnitTest :ime-core:testDebugUnitTest privacyCheck `
  -PrequireRime=true --no-parallel --max-workers=4
./gradlew.bat test privacyCheck :app:assembleDebug :app:assembleDebugAndroidTest `
  :app:assembleRelease :app:lintDebug :app:lintRelease `
  -PrequireRime=true --no-parallel --max-workers=4
```

定向命令通过（25 s）。完整命令独立退出 0，`BUILD SUCCESSFUL in 11m 32s`；805 个任务中 246 执行、27 使用输入匹配的构建缓存、532 已是最新。真实 Rime 三 ABI Debug/Release 构建已完成，未用缺失 native 源码时的降级包代替。Debug 和 Release Lint 均为 `No issues found.`。

| JVM 执行组 | 通过 | 跳过 | failures / errors |
| --- | ---: | ---: | ---: |
| 纯 JVM 模块 | 90 | 0 | 0 / 0 |
| Android 模块 Debug 单元测试 | 1,016 | 2 | 0 / 0 |
| Android 模块 Release 单元测试 | 1,016 | 2 | 0 / 0 |
| 合计 | **2,122** | **4** | **0 / 0** |

这是按变体执行次数，非 2,122 种独立行为；仅统计本轮模块结果目录中的 396 个 XML 文件，不混入历史证据目录。4 次跳过为两个 opt-in 真实 AI 测试在双变体中缺少临时凭据。

六个新 APK 全部通过 `tools/resources/verify-apk.py full <apk> --abis <abi>` 的资源/native 内容校验；三个 Debug 包另通过 `tools/check-apk-installable.ps1` 的包名、ABI 和签名检查。Release 均为 unsigned，不描述为可安装发布包。

| ABI | Debug bytes | Unsigned Release bytes |
| --- | ---: | ---: |
| arm64-v8a | 92,464,362 | 83,945,843 |
| armeabi-v7a | 91,017,886 | 83,284,307 |
| x86_64 | 92,691,070 | 84,163,475 |

日志与摘要：`.tmp-evidence/audit-rereview-targeted-final.log`、`audit-rereview-full.log`、`audit-rereview-jvm-counts.json`、`audit-rereview-apk.log`、`audit-rereview-apk-hashes.json`。新 APK 位于 `app/build/outputs/apk/debug/` 和 `release/`；此前清理任务生成的 ARM64 test-apk 包早于本次 JSON 修复，只保留为历史产物，不能用作当前修复包。

### 9.4 平台验收与剩余边界

JNI 故障注入在本任务启动的 API 37 x86_64 模拟器通过，覆盖空串、畸形 UTF-8 与 JNI 分配失败；日志 `.tmp-evidence/audit-rereview-jni.log`。

补充直接加载 JSON 词典的 Android 正负用例后，再次通过双变体 language-pack 单元测试、测试 APK 编译及 Debug Lint（`BUILD SUCCESSFUL in 1m 12s`）。新增平台用例验证映射数组与 record 优先级，并用真实哈希验证尾部文档、重复键、非 ASCII Unicode 转义和超长 token 会使整包不可用，原 TXT 词条不能掩盖 JSON 失败。

```powershell
./gradlew.bat :language-pack:testDebugUnitTest :language-pack:testReleaseUnitTest `
  :app:assembleDebugAndroidTest :app:lintDebug `
  -PrequireRime=true --no-parallel --max-workers=4
$auditClasses = @(
  'dev.zeroinput.ime.NativeEngineContractTest'
  'dev.zeroinput.ime.NativePinyinTest'
  'dev.zeroinput.ime.EnginePreparationUiTest'
  'dev.zeroinput.ime.LanguagePackJsonPlatformTest'
  'dev.zeroinput.ime.ai.AiProtocolPlatformTest'
  'dev.zeroinput.ime.ai.AiRemoteToolsPlatformTest'
  'dev.zeroinput.ime.clipboard.SecureClipboardVaultReliabilityTest'
  'dev.zeroinput.ime.clipboardguard.ClipboardGuardRuntimeTest'
  'dev.zeroinput.ime.clipboardguard.ClipboardGuardPanelTest'
  'dev.zeroinput.ime.InputExperienceVisualTest'
) -join ','
./tools/test-input-experience.ps1 -Serial emulator-5580 -TestClass $auditClasses `
  -ApkPath 'K:/zeroInput/app/build/outputs/apk/debug/app-x86_64-debug.apk' -Verbose
./tools/test-jni-failures.ps1 -SdkRoot D:/env/AndroidSDK -Serial emulator-5580
```

本轮 API 37 x86_64 平台测试 **52 通过、0 跳过、0 失败**：NativeEngineContract 1、NativePinyin 8、EnginePreparationUi 2、LanguagePackJsonPlatform 9、AiProtocolPlatform 4、AiRemoteToolsPlatform 3、SecureClipboardVaultReliability 7、ClipboardGuardRuntime 12、ClipboardGuardPanel 5、InputExperienceVisual 1。覆盖真实 native 转换、共享清单/词典解析、协议与授权/失败时序和公开 fixture 的尺寸/主题布局断言；未将其描述为实体设备或全部平台时序验证。

设备脚本在执行前后核验了实际安装的主 APK 与测试 APK 哈希：

- x86_64 Debug：`b62e228c4cf10c9955fb51e9354b70ac01dfa71c055a012acc9e30bf81356992`。
- 测试 APK：`4518d3444a3f14500e69271805bd2e8ba2592fd2f5e5031f355fe650db9b1c79`（`app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk`；`outputs` 下的拷贝哈希不同，见 §10.2）。
- 设备报告：`build/device-apk-verification/20261118-050734-90afa3dfd6fa420185aa76e4f03c2b30.json`。
- 日志：`.tmp-evidence/audit-rereview-platform-build.log`、`audit-rereview-device.log`、`audit-rereview-device-counts.json`。

真实 TLS 对端、实体 ARM/OEM、Android 8、全部平台异常路径及 Android 堆峰值/耗时仍未验证，按 §7 保留边界。未新增生产联网路径、权限或加密格式，也未提交/发布。

最终核对：`git diff --check`、本次文档本地链接/锚点和证据路径检查通过；三个临时门禁负向文件均已清理。本任务启动的 `ZeroInput_Clipboard_P1` 模拟器已停止，数据保留。

---

## 10. 第三次独立核查（2026-11-18 13:11–13:30）

### 10.1 基线与取证方式

- 上一轮我更新报告于 12:30（§8 历史）；12:42–13:10 又有一轮「最终再核验」修复 M-4 / L-9、补 H-1 契约，并把报告改写为 458 行（13:09，§9）。本轮从**当前工作区源码与磁盘证据**重新取证，未采信 §9 或 `2026-11-18-followup-verification.md` 的结论。
- 独立核对：当前 `build.gradle.kts` 门禁规则与负向自测全文；`LanguagePackJsonReader.kt`、`LanguagePackDictionaryJson.kt`、`PackDictionaryEntry.kt`、`LanguagePackJson.kt`、`LanguagePackEngine.kt` 全文；`engine-api/.../PersonalizationStore.kt` 的 `clear()` KDoc；新增 JVM / 平台测试；与 `HEAD` 基线 JSON 解析器逐形状比对。
- 独立验证：复跑 Gradle 定向任务；重算 6 个 APK 字节数与 SHA-256、统计磁盘测试 XML、核对 Lint 报告与设备计数；用独立 Java 探针在固定堆上做红绿对照实验。
- **本轮未改动任何产品源码、清单、构建脚本或测试**；只更新本报告，并在 `.tmp-evidence/` 留下探针与日志。

### 10.2 对 §9 声明的复核结果

| §9 声明 | 独立复核 | 结果 |
| --- | --- | --- |
| M-4 门禁扩到 CMake / 主机脚本 / catalog | `build.gradle.kts:82-113`（`toolingTransportViolation` 精确 fetchers 白名单）、`:505-529`（负向自测）、`:585-597`（全仓扫描，`third_party` 显式排除）、`:716-729`（接入 `privacyCheck`） | 一致 |
| 临时违规文件被拒且已清理 | `audit-rereview-gate-negative.log:103-105` 三项同时被拒；三个 `.cmake/.toml/.ps1` 文件当前均不存在；`audit-rereview-targeted-final.log` 复跑 `BUILD SUCCESSFUL in 25s` | 一致 |
| L-9 固定堆红绿实验 | `.tmp-evidence/AuditJsonHeapProbe.java` + `audit-rereview-json-red.log` / `-green.log`：14,000,001 bytes，OOM → `AVAILABLE=true` | 实验本身一致，**但只覆盖单层重复记录，结论不充分**（见 10.3） |
| 全量构建 11m32s / 805 任务 | `audit-rereview-full.log:1890-1891`：`BUILD SUCCESSFUL in 11m 32s`、`805 actionable tasks: 246 executed, 27 from cache, 532 up-to-date` | 一致 |
| JVM 2,122 通过 / 4 跳过 / 396 XML | 独立统计磁盘 396 个 `TEST-*.xml`：executions 2,126、passed 2,122、skipped 4、failures 0、errors 0；`audit-rereview-jvm-counts.json` 分组 90 / 1,016 / 1,016 | 一致 |
| Debug / Release Lint 无问题 | `app/build/reports/lint-results-debug.txt` 与 `lint-results-release.txt` 均为 `No issues found.` | 一致 |
| 六个 APK 大小 | 磁盘 `ls` 与 `audit-rereview-apk-hashes.json` 六项字节数逐一相符 | 一致 |
| 主 APK SHA-256 | 独立 `sha256sum app/build/outputs/apk/debug/app-x86_64-debug.apk` = `b62e228c…56992` | 一致 |
| 测试 APK SHA-256 | `4518d344…b1c79` 对应 **`app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk`**（设备报告 JSON 明载并已实测）；`outputs/apk/androidTest/debug/` 下的拷贝是另一产物，实测 `2dc59ab2…92ae2` | 数值一致；本轮补明路径，避免误判为不一致 |
| 52 项平台测试 / JNI 故障注入 | `audit-rereview-device-counts.json` 与设备报告 `20261118-050734-…json`：52 通过、0 跳过；`audit-rereview-jni.log` 为 `JNI failure injection passed` | 一致 |
| 本轮独立 Gradle 复跑 | `privacyCheck :language-pack:testDebugUnitTest :ime-core:testDebugUnitTest` → `BUILD SUCCESSFUL in 45s`（10 executed / 1 from cache / 103 up-to-date），日志 `.tmp-evidence/audit-thirdcheck-gradle.log` | 新增通过证据 |

### 10.3 新发现 R-1：JSON 折叠的词条上限是「每帧」而非全局（L-9 残留）

- **位置**：`language-pack/src/main/kotlin/dev/zeroinput/languagepack/LanguagePackDictionaryJson.kt:8-13`（`read` 把 `limit` 交给 visitor）、`:21-42`（`DictionaryVisitor` 用 `ArrayDeque<Frame>` 保留所有打开的容器）、`:44`（`Frame(private val limit: Int)` 每帧各持一个 `LinkedHashSet`）、`:111-117`（`add` / `merge` 只按本帧 `target.size < limit` 截断）；`LanguagePackEngine.kt:134`（`MAX_ENTRIES = 50_000`）、`:172`（把 `MAX_ENTRIES - result.size` 作为 `limit` 传入）、`:155`（`recoverable` 按设计不捕获 `Error`）；`LanguagePackJsonReader.kt:153-156`（`MAX_DEPTH=16`、`MAX_OBJECT_KEYS=50_000`、`MAX_ACTIVE_KEYS=100_000`，均不约束所有帧累计词条数）。
- **问题描述**：`read()` 返回根帧的 `entries`，所以**输出**词条数确实不超过 50,000；但每个打开的容器帧都各自保留最多 `limit`（50,000）条 `PackDictionaryEntry`。`Frame.accept` 对 `Kind.OBJECT` 子值执行 `merge(entries, value.entries)`，于是可构造 15 层嵌套数组、每层 50,000 条互异记录，使约 750,000 条对象同时驻留。深度 16 时内部对象会触碰 `MAX_DEPTH` 而使整包拒绝，但 **15 层完全合法**。§9.2 的「有界折叠」只约束了输出与单层输入，未约束跨帧累计。
- **影响范围**：语言包清单自述 `sha256` / `size`（`LanguagePackInstaller.kt:215, 225, 299-300`），安装器只校验实际字节与声明值相符，没有外部信任锚；`MAX_ARCHIVE_BYTES=128 MiB`、`MAX_EXTRACTED_BYTES=256 MiB` 与加载器单文件 16 MiB 上限都允许 12.75 MiB 的构造文件。自带哈希的不可信/畸形语言包即可在低堆设备上触发 `OutOfMemoryError`；`recoverable` 不吞 `Error`（M-8 既定口径），OOM 会向上抛出，可能造成加载线程或应用崩溃。**不含数据泄露、越权或加密格式问题**，严重度评为 **Medium**（可复现的边界缺陷 / 本地可用性 DoS，需用户安装该语言包）。
- **触发/复现场景（本轮独立固定堆实验）**：探针 `.tmp-evidence/AuditNestedJsonHeapProbe.java` 直接调用当前 `LanguagePackEngineFactory.isAvailable()`，生成语法合法、哈希自洽的 JSON 词典；JDK 21、`-Xmx128m`，与 §9.2 宣告修复所用的探针入口和堆上限完全相同：

| 结构 | 文件字节 | 结果 |
| --- | ---: | --- |
| 800,000 行 / 1 层 | 13,600,001 | `AVAILABLE=true`（单帧被 5 万上限截断） |
| 5,000 × 16 层 | 1,360,031 | `AVAILABLE=false`（内部对象达深度 17，被 `MAX_DEPTH` 拒绝） |
| 50,000 × 8 层 | 6,800,015 | `AVAILABLE=true` |
| 50,000 × 12 层 | 10,200,023 | `AVAILABLE=true` |
| 50,000 × 16 层 | 13,600,031 | `REPRODUCED_NESTED_JSON_OOM`，退出 2 |
| **50,000 × 15 层** | **12,750,029** | **`REPRODUCED_NESTED_JSON_OOM`，退出 2（完全合法输入）** |

日志：`.tmp-evidence/audit-thirdcheck-nested-json.log`。单层同尺寸通过、12 层通过、15 层 OOM，排除了文件写入、摘要计算与递归栈等替代解释；放大来源是多层帧各自持有 5 万条。此为桌面 JVM 固定堆实验，**不等同 Android 设备实测堆峰值**，但在同一判据下 §9 的「L-9 已修复」不成立。
- **建议方向**：把 `limit` 改为跨所有帧共享的全局剩余预算（例如传入 `Budget`，`add` / `merge` 统一扣减），子帧 `finish()` 上抛后主动清空已移交的 `entries`；为「嵌套容器累计词条数」补独立硬上限与负向回归测试。保留现有的「达到预算后继续校验语法、重复键、UTF-8、字节数与 SHA-256」行为。
- **判定**：L-9 由「已修复」改判为 **部分修复**；R-1 为本轮新增复核发现，是当前唯一确认待修复项。按用户要求本轮只做核查与报告更新，**未修改产品代码**，修复与回归由维护方决定。

### 10.4 结论与限制

- 20 项原始发现：**16 已修复、1 部分修复（L-9 / R-1）、3 复核撤回**；无 Critical / High 残留。§8 的 12 / 5 / 3 是首次静态复核历史，§9 的 17 / 0 / 3 是 12:42–13:10 的当时结论，均保留以便追溯，不作为当前状态。
- 本轮独立复跑只覆盖 `privacyCheck` 与两个模块的 Debug 单元测试；全量 11m32s 构建、三 ABI 打包与 52 项平台测试仍是核对既有日志与产物（其中哈希、计数、报告文件由本轮独立重算），未由本轮重新执行。
- 固定堆实验为 JDK 21 / 桌面 JVM；Android 实际堆峰值与耗时、实体 ARM/OEM、Android 8 时序、真实恶意 TLS 对端写阻塞仍未验证，按 §7 保留边界。R-1 的「可安装」推断基于清单自述哈希与安装器上限，未在真机上安装恶意样本。
- 报告内链接、APK 路径与证据路径已复核；`.tmp-evidence/` 下新增探针与日志不纳入版本控制。除本报告外，本轮未改动任何产品文件。
