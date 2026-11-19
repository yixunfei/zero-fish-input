# ZeroInput 重构后代码审查反馈（2026-11-18）

修复核查已完成，见[逐项处置与验证](2026-11-18-refactor-fixes.md)：16 项已处理，D-5 经核查撤回。第二轮独立复查见[修复复查](2026-11-18-refactor-recheck.md)（判定 15 项修复、D-2 部分处理、D-3 接受现状、D-5 撤回成立）。以下保留原始审查记录。

| 项目 | 内容 |
| --- | --- |
| 审查对象 | `K:\zeroInput` 当前未提交工作区（`HEAD` = `3632434`，含 92 项已修改文件与 text-steganography 新增模块） |
| 审查日期 | 2026-11-18 18:00–18:40 |
| 审查范围 | 本轮重构涉及的全部主源码与新增文件：`security/message`、`ime-core/stego`、`app/textstego`、`ime-ui/StegoPanelView`、`language-pack` 流式解析、`InputSessionController` 异常收窄、`OpenAiCompatibleProvider` OkHttp 迁移、`recoverable` 迁移、构建门禁扩展 |
| 审查重点 | 功能性 bug、逻辑错误、边界与超时、异常/取消传播、生命周期与线程、隐私边界是否被削弱 |
| 取证方式 | `git diff` 全量分组阅读 + 新增文件全文通读 + 全库模式检索（`runCatching`/`recoverable`/`!!`/阻塞调用/`synchronized`）+ 定向单元测试复跑 |
| 未做 | 未在真实设备/模拟器上运行；未做 UI 截图核对；未复跑 3 ABI 原生构建与 instrumented 测试；未做密码学独立证明 |
| 修改范围 | 仅新增本反馈文档，未改动任何产品源码、测试、构建脚本或清单 |

> **结论摘要**：本轮重构的整体质量较高——密码学格式有独立实现向量交叉验证、隐写载体编解码边界扎实、隐私门禁被显著加强、模块边界没有被破坏。**未发现 Critical 级问题，也未发现敏感数据外泄路径**。共记录 **17 项**：中 6 项（其中 1 项为输入正确性，影响默认中文输入路径）、低 9 项、待确认残留风险 2 项。

## 1. 覆盖矩阵

| 区域 | 实际检查内容 | 结论 |
| --- | --- | --- |
| `security/message`（新增 4 文件） | 信封格式、HKDF 域分离、级联 AEAD、Argon2 参数、密钥文件摘要、UTF-8 边界、明文清零 | 未发现缺陷；向量覆盖充分 |
| `security` 其余 | `EncryptedStore`/`EncryptedFileStore` 契约注释 | 文档性改动，无缺陷 |
| `ime-core/stego`（新增 2 文件） | 载体编解码、标记唯一性、尺寸上限、操作相位机 | 未发现缺陷；`StegoOperationGate` 竞态处理正确 |
| `ime-core` 变更 | `InputSessionController` 异常收窄、`setLanguage` 重构、`CandidateWindow`/`PersonalCandidatePaging`/`PersonalCandidateRanking`/`AsyncCandidateRanker`/`WordAssociationSession` | A-1（见 §3.1） |
| `app/textstego`（新增 8 文件） | 工作区生命周期、凭据持有、作业发布、Activity 授权链、密钥文件读取、交接单次性 | B-1…B-5、C-4（见 §3.2） |
| `app` 其余变更 | `AppGraph`、`ZeroInputService` 隐写集成、`ClipboardImport*`、`ClipboardGuardRuntime`、`AndroidEditorConnection`、`QueuedPersonalizationStore`、`OpenAiCompatibleProvider`/`AiTransportExchange`、`DeviceStage6SensorCapture` | D-4、D-5、E-2 |
| `ime-ui` | `ZeroInputView` 面板/工具栏/布局接入、`StegoPanelView` | C-1 |
| `language-pack` | `LanguagePackJsonReader`、`LanguagePackDictionaryJson`、`PackDictionaryEntry`、`LanguagePackEngine` 流式加载 | D-1 |
| `engine-rime` / `engine-english` / `engine-dictionary` / `engine-api` | JNI UTF-8 解码器、JNI 空串构造、英文组合字符集、单元格词典零进展检测、`PersonalizationStore` 契约 | E-1（见 §3.5） |
| 构建门禁 | `build.gradle.kts` 新增 native/tooling/build/catalog 扫描与其自测 | 覆盖明显增强，未发现绕过 |

## 2. 结论摘要

| 编号 | 严重度 | 一句话描述 |
| --- | --- | --- |
| A-1 | 中 | 个性化候选"待定"窗口内空格被丢弃、候选栏被清空，且该窗口比命名暗示的更宽（含冷启动预热与每次缓存未命中） |
| B-1 | 中 | 解密模式下仍可选择"主凭据方式"，改动后 `prepare()` 必然失败且无法在面板内恢复 |
| B-2 | 中 | `prepare()` 读 `committedText()`、`enqueue()` 读 `submittedChars()`，载波字段有未完成组合时校验对象与实际对象不一致 |
| B-3 | 中 | SAF 密钥文件读取固定 5 秒超时，云端/慢速 provider 的合法文件必然失败 |
| B-4 | 中 | 保存到安全剪贴板存在两个独立 30 秒窗口，慢速认证后静默失败且无提示 |
| B-5 | 中 | 加密作业抛 `Error`（如 OOM）后不发布结果，面板卡在"正在处理"最长 120 秒 |
| C-1 | 低 | 隐写面板打开时工具栏 `☰` 仍可见但点击无响应 |
| C-2 | 低 | 剪贴板导入审阅页对任意文本都显示"本地解密隐写文本"，普通文本点击后进入死路并消耗本次导入 |
| C-3 | 低 | `stegoAction` 的可见性未在 `showReview()` 中恢复，二次进入审阅页时按钮永久消失 |
| C-4 | 低 | `startActivity` 抛异常时交接单不清空，明文最多残留 30 秒 |
| D-1 | 低 | JSON 单 token 4096 字符上限把"跳过超长条目"升级为"整包解析失败" |
| D-2 | 低 | `runCatching`（164 处）与 `recoverable`（53 处）并存，取消/中断在新策略下仍会被局部吞掉 |
| D-3 | 低 | `recoverable` 在 4 个模块各有一份完全相同的实现 |
| D-4 | 低 | `AppGraph.close()` 会触发 `stegoHandoff` 惰性创建并注册一个立刻注销的监听器 |
| D-5 | 低 | `clearAndAwait()` 的 `?: return` 已成死代码 |
| E-1 | 待确认 | `handle()` 只捕获 `EngineOperationFailure`，引擎侧 `Error` 不再回退到编辑器路径 |
| E-2 | 待确认 | `PlatformExchange` 的 `client`/`response` 跨线程读写无 `volatile`，极端时序下新建 client 不会关闭 |

## 3. 问题明细

### 3.1 输入正确性

#### A-1 个性化候选"待定"窗口吞掉空格并清空候选栏（中）

**位置**

- `ime-core/src/main/kotlin/dev/zeroinput/ime/core/InputSessionController.kt:171-180`
- `ime-core/src/main/kotlin/dev/zeroinput/ime/core/PersonalCandidatePaging.kt:103-105`
- `app/src/main/kotlin/dev/zeroinput/ime/personalization/QueuedPersonalizationStore.kt:80, 95-98`
- `app/src/main/kotlin/dev/zeroinput/ime/personalization/DictionaryPersonalizationStore.kt:17, 26`

**现象**

在中文会话中，若此刻个性化分页处于"未就绪"，按下空格不会有任何反应：不提交候选、不插入空格、组合串保持原样。同时候选栏被清空，直到后台查询返回才重新出现。回车键不受此限制，会直接提交原始拼音，因此空格与回车在同一状态下行为不一致。

**原因**

`PersonalCandidatePaging.publish()` 用 `editsPending = editsApplied && !loaded.ready` 判定"词典编辑尚未落地"，一旦成立就 `return native.copy(candidates = emptyList())`，而 `InputSessionController.handle()` 在 `editsPending` 时直接 `return` 丢弃空格。

问题在于 `ready=false` 的表达范围远大于"编辑未落地"：

1. `QueuedPersonalizationStore.suggestionPage()` 在**缓存未命中**时被明确设计为非阻塞——先 `scheduleSuggestionQuery()` 再返回 `unavailablePage()`，而后者固定 `ready=false`。
2. 该函数的 `dictionaryEditsApplied` 取自 `DictionaryPersonalizationStore.requiresFiltering`，该字段默认 `true`，只有 `prepare()` 成功执行后才可能变为 `false`。因此**冷启动首次预热完成前**，所有中文用户都会命中该状态。

净效果：对全部中文用户在会话最开始的一小段窗口内、以及对启用了"词典编辑"功能的用户在每一次新音节缓存未命中时，都会出现"空格丢失 + 候选栏闪烁"。原审计把该行为记录为"待定编辑保留组合串"，但未区分"编辑未落地"与"答案未缓存"，因此低估了触发范围。

**建议**

- 把"缓存未命中"与"编辑未落地"拆成两个字段（例如 `answerPending` / `editsUnproven`），只有后者才允许隐藏候选。
- `answerPending` 期间保留上一次可用页面，避免候选栏清空。
- 空格改为"排队 + 查询返回后补发"，或至少退化为旧的"提交原始拼音 + 空格"，不要静默丢弃按键。
- 若保留当前策略，需在文档与测试中明确"回车提交、空格丢弃"的不一致，并补一条覆盖该状态的回归测试。

### 3.2 隐写（text steganography）功能

#### B-1 解密模式下"主凭据方式"可改且改后必然失败（中）

**位置**

- `app/src/main/kotlin/dev/zeroinput/ime/textstego/StegoWorkspace.kt:186-194`
- `app/src/main/kotlin/dev/zeroinput/ime/textstego/StegoOptions.kt:42-47`

**现象**

解密时打开"选项"，第一项是"主凭据方式"。用户只要改动它，之后每次点击"生成/解密"都会落到 `MessageFailure` 的通用提示（"无法完成操作…"），并且没有入口把选项改回载波头声明的模式，只能关闭面板重新导入。

**原因**

`prepare()` 对解密分支做了 `if (options.mode != requirements.mode) throw MessageFailure()`，而 `StegoOptionsDialog.show()` 在 `decrypt=true` 时仍把 `stego_credential_mode` 放进可选项。载波头里的模式是权威值，用户改动不会改变信封。

**建议**

解密时隐藏该选项（载波已决定模式），或在 `prepare()` 中直接用 `requirements.mode` 覆盖 `options.mode` 并刷新界面摘要。

#### B-2 `prepare()` 与 `enqueue()` 读取草稿的接口不一致（中）

**位置**

- `StegoWorkspace.kt:188`（`committedText()`）
- `StegoWorkspace.kt:204-206`（`submittedChars()`）

**现象**

解密前校验用 `drafts[CARRIER].committedText()`，真正执行时用 `submittedChars()`。后者会先 `finishComposition()`，前者不会。当载波字段存在未完成的英文组合（在载波字段输入字母即会产生）时，校验的是旧文本、执行的是新文本：轻则误报失败，重则校验通过但解密用另一串文本而失败。

**建议**

统一使用 `submittedChars()`（或在校验前显式 `finishComposition()`），保证"检查的对象"与"使用的对象"同一。

#### B-3 密钥文件读取固定 5 秒超时（中）

**位置**

- `app/src/main/kotlin/dev/zeroinput/ime/textstego/StegoSourceActivity.kt:293`（`resetDeadline(5000)`）
- 同文件 `:303-310`（`valid()` 以 `importDeadline` 判定）

**现象**

用户选择密钥文件并确认后，读取必须在 5 秒内完成，否则 `factor == null`，提示"无法完成操作"并结束会话。对 1 MiB 上限的文件，从云端 SAF provider 或慢速存储读取很容易超过 5 秒。

**建议**

与该流程其他步骤保持一致（30–120 秒），或按文件大小给出预算；超时提示应区分"读取超时"与"文件无效"。

#### B-4 保存到安全剪贴板的两个 30 秒窗口（中）

**位置**

- `StegoSourceActivity.kt:193`（`authenticate()` 的 `resetDeadline(30_000)`）
- `app/src/main/kotlin/dev/zeroinput/ime/textstego/StegoHandoff.kt:20-26`（`Request` 构造即创建 `PendingClipboardAddition`）
- `app/src/main/kotlin/dev/zeroinput/ime/clipboard/PendingClipboardAddition.kt:39`（`TIMEOUT_MILLIS = 30_000`）

**现象**

用户点击"保存"后，`Request` 立刻持有明文并在其构造时开始计时；随后还要经过系统身份认证（可能重试、可能输入 PIN）与一次前台确认点击，才能调用 `consume()`。同时 Activity 自身的 `deadline` 也是从点击"保存"起算 30 秒。两条路径任一超时：前者抛 `CancellationException` 被 `catch (_: Exception) { false }` 吞掉，后者直接 `discard()`，两者都只表现为通用失败或界面直接关闭，没有"重新认证"入口。

**建议**

认证成功后再创建 `PendingClipboardAddition`（或为保存流程单独使用更长的时间预算），并在超时时给出可重试的明确提示而不是静默关闭。

#### B-5 加密作业抛 `Error` 后操作相位卡住（中）

**位置**

- `app/src/main/kotlin/dev/zeroinput/ime/textstego/StegoCryptoTask.kt:46-60`
- `ime-core/src/main/kotlin/dev/zeroinput/ime/core/stego/StegoOperationGate.kt:13, 18-29`

**现象**

`run()` 只 `catch (_: Exception)`。若 `process()` 抛出 `OutOfMemoryError`（高内存档位 Argon2 在低内存设备上是可预期的），`finally { close() }` 会清密钥，但不会通知工作区：`pending` 仍非空、`gate.phase` 停在 `WORKING`。界面保持"正在本机处理…"，直到 `gate` 的 120 秒截止触发关闭，并显示"隐写操作已失效"，与真实原因不符。

**建议**

`run()` 增加 `catch (failure: Throwable)` 分支（`Error` 也要发布一个失败结果或直接 `gate.fail`），至少让面板立即回到可编辑状态；`reserveHeadroom()` 只是启发式，不能替代该兜底。

### 3.3 界面与交互一致性

#### C-1 隐写面板打开时工具栏 `☰` 可见但无响应（低）

`ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/ZeroInputView.kt:1295` 只以 `!manualTools && !isShapeLookupOpen && !isPrivateDraftOpen` 决定 `toolsButton` 可见性，而 `showTools()`（`:929-937`）在 `isTextStegoOpen` 时直接 `return`。因此在隐写面板中该按钮可见却无效；同一行的返回箭头（`canNavigateBack` 为真）才是实际可用入口。

建议把 `isTextStegoOpen` 加入 `toolsButton` 的隐藏条件。

#### C-2 剪贴板导入审阅页无条件提供"本地解密隐写文本"（低）

`app/src/main/kotlin/dev/zeroinput/ime/clipboard/ClipboardImportView.kt:65-71` 的按钮没有可见性条件，`showReview()`（`:113-119`）也不区分内容。对普通文本点击它：`importStegoText()` 会 `takeForStego()`（消费并关闭本次导入）→ 私密宿主解析失败 → 通用失败提示。用户失去本次导入且没有回到原文的路径。

建议在 `ClipboardImportText.parse()` 已能识别载波标记的基础上，把"是否为载波"作为独立标志传给视图，只在载波时显示该按钮。

#### C-3 `stegoAction` 可见性未在 `showReview()` 中恢复（低）

同一文件：`clearText()`、`showBusy()`、`showAuthorized()` 都会把 `stegoAction` 置为 `GONE`，但 `showReview()` 不像 `aiAction` 那样重新赋值。当前流程中审阅页只出现一次，所以尚未表现为用户可见缺陷；一旦后续加入"认证失败后返回审阅"或复用该视图，按钮会永久消失。

建议在 `showReview()` 内显式设置可见性，与 `aiAction` 保持一致。

#### C-4 `startActivity` 失败时交接单残留明文（低）

`app/src/main/kotlin/dev/zeroinput/ime/ZeroInputService.kt`（`launchStegoSource`）：`graph.stegoHandoff.put(request)` 之后直接 `startActivity(...)`，未捕获 `ActivityNotFoundException`/`SecurityException`。异常会向上抛到 `handleKeyboardAction` 的调用栈，而 `Request`（可能持有解密结果或待保存明文）留在 `StegoHandoff` 中直到 30 秒超时。

建议 `try/catch` 后立即 `graph.stegoHandoff.take(token)` 并 `close()`（或新增 `discard(token)`）。

### 3.4 工程一致性

#### D-1 JSON 单 token 长度上限把"跳过"升级为"整包失败"（低）

`language-pack/src/main/kotlin/dev/zeroinput/languagepack/LanguagePackJsonReader.kt:96, 154` 新增 `MAX_TOKEN_CHARS = 4096`，超限即 `require` 失败。旧实现中单个超长取值只会被 `PackDictionaryEntry.create()` 丢弃，其余条目照常加载；现在任意一个超过 4096 字符的字符串会让整个词典（进而整个语言包）加载失败并表现为"该输入法不可用"。

建议：词典 JSON 的超长标量降级为"跳过该条目"，只有结构性错误才整包失败；或按文件类型区分上限（清单 vs 词典）。

#### D-2 异常策略迁移不完整（低）

全库仍有 `runCatching` 164 处、`recoverable` 53 处。文档声明的新策略是"取消/中断/致命错误必须传播，普通异常才降级"，但以下属于输入或生命周期路径的位置仍在用 `runCatching`，会吞掉取消与中断：

- `app/src/main/kotlin/dev/zeroinput/ime/EngineWarmupCoordinator.kt:103-128`（引擎准备与 `start`）
- `app/src/main/kotlin/dev/zeroinput/ime/EngineWarmupResultDelivery.kt:49, 66, 79, 83`
- `app/src/main/kotlin/dev/zeroinput/ime/settings/SettingsRepository.kt:30`（变更监听回调）

建议把这几处作为下一批迁移对象（其余 `runCatching` 若确属"纯可选、结果无关"可保留，但应在文档中列出例外清单）。

#### D-3 `recoverable` 重复实现（低）

同一份 14–15 行实现存在于：

- `app/src/main/kotlin/dev/zeroinput/ime/concurrency/RecoverableOperation.kt`
- `ime-core/src/main/kotlin/dev/zeroinput/ime/core/RecoverableOperation.kt`
- `engine-rime/src/main/kotlin/dev/zeroinput/engine/rime/RecoverableOperation.kt`
- `language-pack/src/main/kotlin/dev/zeroinput/languagepack/RecoverableOperation.kt`

`app` 已经依赖 `ime-core`，至少这两份可以直接复用。若坚持按模块隔离，建议在其中一处写上"刻意复制，修改需同步"的说明，否则后续极易出现四份行为漂移（例如只给其中一份加 `LinkageError` 处理）。

#### D-4 `AppGraph.close()` 触发惰性创建（低）

`app/src/main/kotlin/dev/zeroinput/ime/AppGraph.kt:495` 的 `stegoHandoff.destroy()` 会让 `by lazy` 在关闭阶段首次实例化 `StegoHandoff`——其构造函数会注册一个 `SettingsRepository` 监听器，随即被 `destroy()` 注销。功能无害，但属于"关闭路径产生副作用"的坏模式。

建议改为 `if (stegoHandoffInitialized) stegoHandoff.destroy()`，或使用 `Lazy` 句柄判空。

#### D-5 `clearAndAwait()` 死代码（低）

`app/src/main/kotlin/dev/zeroinput/ime/personalization/QueuedPersonalizationStore.kt:227` 的 `?: return` 在 `enqueueClear()` 改为"关闭时抛异常、其余情况返回非空"之后已不可达。留着容易让读者误以为存在"未入队但成功"的路径。

### 3.5 需确认的残留风险

#### E-1 引擎 `Error` 不再回退到编辑器路径（待确认）

`InputSessionController.handle()` 的兜底从 `catch (_: Throwable)` 收窄为 `catch (_: EngineOperationFailure)`（`InputSessionController.kt:216`），而 `engineOperation()` 只把 `Exception` 包装成 `EngineOperationFailure`，`Error` 原样穿透。全库只有 `DictionaryEditReading.kt:38` 与 `RimeRuntime.kt:189` 分别处理了 `UnsatisfiedLinkError` 和初始化期的 `LinkageError`，按键路径没有做任何转换。

即：如果原生库在初始化成功后仍可能在 `handle()` 中抛 `LinkageError`（例如 ABI 不匹配的部分符号、热更新残留），IME 进程会直接崩溃，而不是像以前那样退化为"直通编辑器"。这是有意收紧的策略，但它把"降级"变成了"崩溃"，风险方向相反。

建议：在 `RimeEngine`/`FallbackEngine` 适配层把 `LinkageError` 统一转换为引擎失败异常（或至少在 `handle()` 外层保留 `catch (error: LinkageError)`），并补一条负向测试。

#### E-2 OkHttp exchange 的跨线程可见性（待确认）

`app/src/main/kotlin/dev/zeroinput/ime/ai/OpenAiCompatibleProvider.kt` 的 `PlatformExchange` 中，`call` 与 `closed` 是原子类型，但 `client`、`response`、`method` 是普通字段，`prepare()` 在 worker 线程写、`disconnect()` 可能在取消线程读。若取消发生在 `prepare()` 之前，`client == null` 使 `disconnect()` 什么也不做，随后 `prepare()` 新建的 client 不会被关闭：`send()` 会因 `closed` 抛异常，但该 client 的 dispatcher 线程最多滞留 60 秒。

实际影响有限（`call.cancel()` 是主要取消手段，且 `exchange()` 的 `finally` 总会 `disconnect()`），但建议把 `client`/`response` 标为 `@Volatile`，并在 `send()` 发现 `closed` 时主动关闭自己刚创建的 client。

## 4. 已验证通过的部分

以下为本轮实际核对并认为**无需修改**的高风险点，供后续复查参考：

- **信封格式**：域分离 HKDF、每层独立随机 nonce、元数据与正文分级 AAD、封面绑定、层级/宽度枚举在 KDF 之前校验、失败不释放任何明文；`security` 的交叉向量覆盖 12 组算法-宽度组合（AES/Twofish/Camellia 各 3 档、XChaCha20/Kuznyechik/SM4 各 1 档），另含 3 档 Argon2、5 种凭据根与 1/6 层完整信封摘要。
- **载体编解码**：标记唯一性、只接受一个终结块、非规范 Base64 拒绝、最大信封/封面/UTF-16/UTF-8 四重上限一致；`StegoCarrierTest` 覆盖逐字节往返与破损样例。
- **相位机与取消**：`StegoOperationGate` 的取消不释放运行中的 worker、过期不复活、`consume` 先失效后调用外部编辑器；`StegoCryptoOperation` 的 `offer/take/close` 全部同步，晚到结果被丢弃并清零。
- **交接单**：仅传不透明 ID，绑定编辑器标识、代次与 30 秒期限，单次取用；屏幕关闭、设置变更、剪贴板删除都会撤销。
- **JNI Unicode 修复**：新增首字节范围检查、最短编码校验、代理区拒绝、`end - cursor` 边界，以及用有效存储构造空串——都是真实的健壮性修复。
- **构建门禁**：新增 native/CMake/host-script/catalog/data-asset 扫描，并对三个已批准传输增加了"必须保留超时、禁用代理/重定向/重试"的正向断言，门禁自测同时覆盖了负例。
- **流式词典解析**：解析与哈希共用同一条有界流，输出条目预算之外仍继续校验语法、UTF-8、重复键与哈希，避免了旧的整树分配。
- **隐私边界没有被削弱**：`StegoSourceActivity` 为 `exported=false` + `excludeFromRecents` + `FLAG_SECURE` + 无状态保存；载波标记会被排除在 AI 导入之外；未新增权限、剪贴板正文读取或联网路径。

## 5. 验证命令与结果

```bash
# 1) 三个核心模块强制复跑（--rerun-tasks，真实执行，非缓存命中）
./gradlew :security:testDebugUnitTest :ime-core:testDebugUnitTest \
          :language-pack:testDebugUnitTest --rerun-tasks --offline
# BUILD SUCCESSFUL in 10s / 53 actionable tasks: 53 executed
# security 27 / ime-core 191 / language-pack 62 用例，0 失败 0 跳过

# 2) 其余 JVM 模块
./gradlew :engine-api:test :engine-english:test :engine-dictionary:test \
          :user-data:test :model-scoring:test --offline
# BUILD SUCCESSFUL

# 3) app 单元测试（本轮源码已构建并通过）
./gradlew :app:testDebugUnitTest --offline
# BUILD SUCCESSFUL / 525 用例，0 失败，2 跳过（需临时凭据的 live AI 用例）

# 4) 隐私门禁
./gradlew privacyCheck testPrivacyBoundary --offline
# BUILD SUCCESSFUL
```

合计 **805 条单元测试、0 失败、2 条设计内跳过**；`privacyCheck` 与 `testPrivacyBoundary`（含新增负例自测）通过。本轮未复跑 `assembleDebug`/`assembleRelease`、Lint、instrumented 测试与 JNI 故障注入。

## 6. 未覆盖范围与建议后续

1. **设备侧**：隐写面板在 240/320 dp 等窄屏上的实际排版（主工具栏从 7 个按钮增至 8 个）、系统 PIN 认证的真实时序（直接关系 B-4 的 30 秒窗口）、慢速 SAF provider（直接关系 B-3）。
2. **原生**：`LinkageError` 在按键路径的可达性（E-1）需要一次故障注入实验，而不是静态推断。
3. **密码学**：本报告只做了实现一致性审查，未做独立密码学证明；`MessageEnvelope` 的自校验（`seal()` 后立即 `openWithRoot`）会重复整条级联，属可接受的性能代价，但也意味着加密路径的正确性依赖"自校验 + 向量"而非外部证明。
4. **明文生命周期**：`prepared`（编码后文本）、`result?.let(::String)`、`String(chars)` 都是不可擦除的 `String` 副本，与"明文尽快清零"的目标存在天然差距。建议在设计文档中显式声明这一已接受限制，避免后续被误读为遗漏。
5. **建议的修复顺序**：A-1 → B-3/B-4（用户可感知的失败）→ B-1/B-2/B-5（面板内死路）→ E-1（策略确认）→ C/D 类整理。
