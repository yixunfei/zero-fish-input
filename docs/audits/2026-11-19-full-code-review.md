# ZeroInput 全量代码审查报告

> 后续逐项核查与修复见 [核查记录](2026-11-19-full-code-review-fixes.md)。本报告保留静态审查原结论；其中多项未获实现契约或复现证据支持，不能视为已确认缺陷。

| 项目 | 内容 |
| --- | --- |
| 审查对象 | `K:\zeroInput` 当前工作区源码（未假定干净工作区，未依赖 Git 基线） |
| 审查日期 | 2026-11-19 |
| 报告生成 | 12:09（GMT+8） |
| 审查类型 | 静态源码审查；**未执行**构建、Lint、单元/仪器测试与真机验证 |
| 审查范围 | 12 个 Gradle 模块主源码（435 个 `.kt`，51,749 行），重点为 IME 会话状态机、候选与分页、安全剪贴板、AI 传输与设备代理、原生 Rime、语言包安装、UI 视图 |
| 严重度口径 | 沿用 `docs/audits/2026-11-17-code-audit.md` §0 |
| 结论 | **0 Critical / 1 High / 4 Medium / 5 Low**，合计 10 项；未发现内存安全、路径穿越、认证绕过或原生崩溃类缺陷 |

> **结论摘要**：本轮审查在输入主链路（`InputSessionController` → `PersonalCandidatePaging` → 候选视图）、安全存储、原生引擎与安装器上均未发现可导致崩溃、数据泄露或不可恢复数据丢失的问题。唯一 High 为设备代理动作类别的 **fail-open 默认值**（`SettingsRepository`），属授权模型缺陷而非代码错误，修复成本约 1 行加一次迁移。4 项 Medium 集中于「分段编辑的偏移映射」「搜索草稿重绘」「无障碍参数契约」「模型重排被异步刷新撤销」，均影响手感或可判定性，建议同批修复。

---

## 0. 方法与判定口径

- **取证方式**：小模块逐文件通读全部主源码；大文件按职责分段读取完整函数体（`ZeroInputService` 2,329 行、`ZeroInputView` 1,489 行、`InputSessionController` 992 行、`AiWorkbenchPanelView` 794 行、`DeviceAgentAccessibilityService` 786 行、`QueuedPersonalizationStore` 551 行、`SecureClipboardVault` 544 行等）；对全库执行模式检索（`!!`、空 `catch`、`TODO/FIXME`、`GlobalScope`/`runBlocking`、`Thread.sleep`、`registerReceiver`/`unregisterReceiver`、`registerContentObserver`、`Cursor` 与 `InputStream` 释放、`substring`/`dropLast`/`indexOf` 边界、`first()/last()/single()`）后逐条回到源码确认。
- **复核原则**：每条候选缺陷都必须能指出触发路径与失败后果，否则列入 §3「已排除」而不计入统计。本轮共排除 5 项一度标记的可疑点（含 2 项初判为崩溃、后证实不成立）。
- **严重度定义**：
  - **Critical**：无需特殊条件即可造成敏感数据泄露、越权或不可恢复的数据丢失。本轮未发现。
  - **High**：在特定但可预期的条件下导致用户数据未被删除、功能永久失效或输入内容被错误提交。
  - **Medium**：可复现的功能/并发/边界缺陷，或明显削弱防护纵深与失败可判定性。
  - **Low**：健壮性、边界、可维护性缺陷与轻微功能限制。
- **行号约定**：均指向本报告生成时的工作区源码，区间表示相关函数/代码块范围。

---

## 1. 覆盖矩阵

| 模块 | 主源码 | 本轮实际检查内容 | 结果 |
| --- | --- | --- | --- |
| `app` | 210 文件 / 28,768 行 | `ZeroInputService` 全文；`AndroidEditorConnection`、`EditorSelectionState`、`UnicodeDeletionBoundary`、`LocalDraftInput`、`BoundedExecutors`、`QueuedPersonalizationStore`、`SettingsRepository`、`SettingsDeletionCoordinator`、`AppGraph`、`EngineWarmup*`；剪贴板四件套；AI 传输/工作台/上下文/流式投递/文件工具/思考文本解析；设备代理协调器与无障碍服务；`glide`、`handwriting` 协调器 | H-1、M-2、M-3、M-4、L-1、L-2、L-5 |
| `ime-core` | 20 文件 / 2,177 行 | `InputSessionController` 全文；`CandidateWindow`、`PersonalCandidatePaging`、`WordAssociationSession`、`RecentComposition`、`RecoverableOperation`、`EditorPrivacyPolicy` | M-4、相关说明见 §3 |
| `ime-ui` | 72 文件 / 9,021 行 | `ZeroInputView` 全文；`KeyboardPanel`、`CandidateStripView`、`CompositionEditorView`、`AiWorkbenchPanelView`、`CandidateRowContinuity` | M-1、M-2、L-3 |
| `security` | 11 文件 / 695 行 | `EncryptedFileStore`、`AesGcmEnvelope`、`AesGcmKeyStore`、`AuthenticationGrant` 全部通读 | 未发现缺陷 |
| `user-data` | 23 文件 / 2,839 行 | `SecureClipboardVault` 全文、`QueuedPersonalizationStore` 契约、清除/世代逻辑 | L-4 |
| `ai-api` | 16 文件 / 1,473 行 | `AiContext`、`AiLimits`、`DeviceActionArguments`、`AiEndpoint` 契约 | 未发现缺陷 |
| `engine-rime` | 25 文件 / 2,662 行 | `RimeRuntime`、`RimeInputEngine`、`RimeAssetInstaller`、`RimeSessionVerifier` | 未发现缺陷 |
| `language-pack` | 23 文件 / 2,126 行 | `LanguagePackInstaller`（校验和、大小限制、路径策略） | 未发现缺陷 |
| `engine-api` | 13 文件 / 476 行 | 端口与不可变模型抽查 | 未发现缺陷 |
| `engine-english` | 4 文件 / 336 行 | 引擎主体抽查 | 未发现缺陷 |
| `engine-dictionary` | 6 文件 / 471 行 | 导入格式与联想索引抽查 | 未发现高置信缺陷（见 §6） |
| `model-scoring` | 12 文件 / 705 行 | 手写/评分模型封装抽查 | 未发现高置信缺陷（见 §6） |

全库静态扫描结果：`TODO`/`FIXME`/`HACK` **0** 处；非空断言 `!!` **0** 处；空 `catch {}` **0** 处；`GlobalScope`/`runBlocking` **0** 处；`Thread.sleep` 仅 1 处（设备代理 `wait` 动作，运行于后台执行线程且含中断处理，见 §3）。

---

## 2. 问题清单

### 2.1 High

#### H-1 设备代理动作类别默认全量放开（fail-open）

| 项 | 内容 |
| --- | --- |
| 位置 | `app/src/main/kotlin/dev/zeroinput/ime/settings/SettingsRepository.kt:97-103` |
| 类型 | 授权模型 / 安全默认值 |
| 严重度 | **High** |

**问题描述**

`deviceAgentAllowedCategories` 在偏好键缺失时直接返回**全部**动作类别：

```kotlin
var deviceAgentAllowedCategories: Set<dev.zeroinput.ai.api.DeviceActionCategory>
    get() {
        val values = preferences.getStringSet("ai.device-agent.categories", null)
            ?: return dev.zeroinput.ai.api.DeviceActionCategory.entries.toSet()   // 全部允许
        return dev.zeroinput.ai.api.DeviceActionCategory.entries.filter { it.name in values }.toSet()
    }
```

只有在键**存在且被显式写空**时才返回空集。该值经 `AppGraph.kt:212` 直接注入 `allowedCategories`，成为设备代理可用工具集合的上界。

**产生原因**

把「从未配置」与「全部允许」等同。同文件 `:105` 对 Stage 6 能力的注释明确写着「Stage 6 domains require explicit, independent opt-in and default to none」，与之相邻的基础类别却采用相反策略，属实现与声明不一致。

**影响**

- 全新安装、或从早期版本升级（该键尚未写入）时，设备代理在用户**从未逐项确认**的情况下即拥有点击、滑动、文本输入、返回等全部基础动作类别。
- 用户若只想开放「读屏」一类能力，必须先在设置页逐项关闭其余全部类别，而不是逐项开启，交互与安全模型相反。
- 与项目其他位置的 fail-closed 基调（`AesGcmKeyStore` 的 `allowKeyCreation=false`、`EditorPrivacyPolicy` 的保守默认、Stage 6 的默认零授权）不一致。

**判定说明**

利用该默认值仍需用户显式开启设备代理总开关并授予无障碍权限，因此未按 Critical 计；但总开关是引导式的一次性操作，而细粒度类别本应是二道防线，故计为 High 而非 Medium。

**修复建议**

```kotlin
get() {
    val values = preferences.getStringSet(KEY_DEVICE_AGENT_CATEGORIES, null)
        ?: return emptySet()                       // 未配置 = 未授权
    return DeviceActionCategory.entries.filter { it.name in values }.toSet()
}
```

配套两步：

1. 在 `DeviceAgentSettingsActivity` 首次进入时，把**推荐子集**（而非全集）写入偏好，形成显式快照；
2. 对旧版本升级做一次迁移：读取旧键，若等于全集则认为「用户未表态」，收敛到推荐子集并提示。

---

### 2.2 Medium

#### M-1 `CompositionEditorView` 的 raw↔display 偏移映射在分段选择时错位

| 项 | 内容 |
| --- | --- |
| 位置 | `ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/CompositionEditorView.kt:104-113`（`setOffsets`）；消费点 `:163`（`offset(event)`）、`:179`（`onDraw`）、`:61`（无障碍 `setTextSelection`） |
| 类型 | 界面交互 / 边界条件 |
| 严重度 | **Medium** |

**问题描述**

双向偏移表由单指针贪心匹配建立：

```kotlin
private fun setOffsets(raw: String, display: String) {
    rawOffsets = IntArray(display.length + 1)
    displayOffsets = IntArray(raw.length + 1)
    var offset = 0
    for (index in display.indices) {
        if (raw.getOrNull(offset) == display[index]) offset++
        rawOffsets[index + 1] = offset
        displayOffsets[offset] = index + 1
    }
}
```

**产生原因**

`display` 并非 `raw` 的纯前缀：分段选择时 `render()` 会拼出 `"ni hao · xiang"`（`:74-81`），候选带读音时走 `pinyin_matched_reading` 资源串（`:89-93`）。一旦某处字符不相等，`offset` 停住，之后所有 `display` 位置都折叠到同一个 raw 偏移；反过来，**从未被访问到的 raw 偏移在 `displayOffsets` 中保持初值 0**。真实对齐关系其实已由 `snapshot.candidates[i].pinyinMatch.start/end` 给出，此处未使用。

**影响**

- 分段编辑（已选音节、可撤销）状态下，点击或拖动选区落到相邻段、或直接落到段首；
- `onDraw` 用 `displayOffsets[range.lower]` 绘制高亮区间，出现错位高亮；
- 无障碍服务通过 `setTextSelection` 上报的选区位置错误，TalkBack 朗读范围不符。

不涉及崩溃：所有下标都落在数组界内，只是**语义错误**。

**修复建议**

按 `pinyinMatch` 三段显式构造映射，替代字符贪心：

```kotlin
// 0..start → 恒等；start..end → spelling；end..raw.length → 后缀
// 未覆盖项用前向填充，禁止保留 0
```

无法取得 `pinyinMatch`（`match == null`）时，`display` 必等于 `raw`（`render()` 的三条分支已保证），可直接做恒等映射。若两者既无 `pinyinMatch` 又不相等，应退化为恒等映射并禁用分段手势，而不是继续贪心。

---

#### M-2 搜索草稿重绘时可能整段丢失已上屏文本

| 项 | 内容 |
| --- | --- |
| 位置 | `ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/ZeroInputView.kt:709-711` |
| 类型 | 边界条件 / 显示正确性 |
| 严重度 | **Medium** |

**问题描述**

```kotlin
val committed = text.dropLast(composition.length)
val offset = searchCaret?.offset?.coerceIn(0, committed.length) ?: committed.length
emoji.renderQuery(committed.substring(0, offset) + composition + committed.substring(offset))
```

**产生原因**

`String.dropLast(n)` 在 `n > length` 时返回**空串**，不做下限保护。引擎先 `publish` 组合串、宿主草稿文本后到时，`composition.length > text.length` 成立，`committed` 变为 `""`，本帧渲染内容退化为「只有组合串」。`offset` 已 `coerceIn`，因此 `substring` 不会越界——这是纯粹的**静默丢字**，没有异常可捕获。

**影响**

搜索面板偶发闪烁：已上屏字符在一帧内消失，随后又出现。用户观感为「输入被吞」。

**修复建议**

```kotlin
val committed = if (composition.length <= text.length) text.dropLast(composition.length) else text
```

更严格的做法是先断言 `text.endsWith(composition)`；不成立说明宿主草稿与引擎状态已不一致，应整帧跳过重绘并等待下一次同步，而不是渲染一个结构错误的字符串。

---

#### M-3 `swipe` 动作强取参数，依赖跨函数的隐式契约

| 项 | 内容 |
| --- | --- |
| 位置 | `app/src/main/kotlin/dev/zeroinput/ime/ai/device/DeviceAgentAccessibilityService.kt:401-404` |
| 类型 | 异常处理 / 契约脆弱 |
| 严重度 | **Medium** |

**问题描述**

```kotlin
val startX = call.arguments.getValue("start_x").toInt()
val startY = call.arguments.getValue("start_y").toInt()
val endX = call.arguments.getValue("end_x").toInt()
val endY = call.arguments.getValue("end_y").toInt()
```

`Map.getValue` 在键缺失时抛 `NoSuchElementException`；`.toInt()` 在类型不符时抛 `ClassCastException`。

**产生原因**

此处安全性完全依赖 `DeviceActionArguments.valid` 已先行校验（要求 `swipe` 的四个坐标存在且为 `INTEGER`，取值范围 `0..100000`）。这是一条**跨函数、无类型承载**的隐式契约：编译器不检查，重构不报警，测试也只在「校验先行」的路径上通过。

**影响**

一旦校验被放宽（模型工具集演进、新增直调路径、`DeviceActionCall` 从其他入口构造、后续把 `valid` 改成 warn-only），异常会在设备动作执行线程上抛出。轻则整个动作以内部错误失败、任务被判定异常终止；重则若上游未包裹，线程崩溃并连带中断当前任务。

**修复建议**

```kotlin
val startX = (call.arguments["start_x"] as? Number)?.toInt()
    ?: return rejected(call, "Swipe coordinates are invalid")
```

并在 `execute()` 入口对 `call` 再断言一次 `DeviceActionArguments.valid(call)`，形成「入口 + 使用点」双重保险；`swipe` 内部改用 `requireNotNull` 之外的显式拒绝语义，保持 `rejected` 与 `failed` 的可区分性。

---

#### M-4 异步个人化刷新会静默丢弃模型重排结果

| 项 | 内容 |
| --- | --- |
| 位置 | `ime-core/src/main/kotlin/dev/zeroinput/ime/core/InputSessionController.kt:833-835`（`publish` 构造 `InputSessionState`）；触发点 `:288`（`refreshPersonalization`）；对照 `:320`（`applyModelWinner`）、`:334`（`clearModelRanking`） |
| 类型 | 并发 / 状态机 |
| 严重度 | **Medium** |

**问题描述**

`publish()` 重建会话状态时未透传 `modelRanked`，该字段回落为默认值 `false`：

```kotlin
state = InputSessionState(language, privacy, visibleSnapshot, languagePackKey, engine?.descriptor,
    canReconvert = recentComposition.available && !visibleSnapshot.isComposing,
    candidateRevision = state.candidateRevision + 1)
// InputSessionState.modelRanked 默认 false
```

而 `applyModelWinner()` 刚刚通过 `copy(snapshot = promoted, modelRanked = true, ...)` 把模型重排结果发布出去。

**产生原因**

`publish()` 只区分「引擎快照是否变化」，不区分「本次重新发布的原因」。个人词频查询是异步的：查询完成后 `refreshPersonalization()` 调用 `publish(rawEngineSnapshot)`，用**未重排**的引擎快照覆盖当前状态。`clearModelRanking()` 的存在说明「撤销重排」本应是显式动作，此处却成了刷新个人候选的副作用。

**影响**

用户看到模型重排后的顺序、手指正在落下时，个人化查询返回，列表突然恢复引擎原序：

- 点错候选（提交了错误的词）；
- 重排计算与 `ModelRankingPolicy.promote` 被浪费；
- 该跳变不可预测（取决于词频查询耗时），难以复现与测试。

**修复建议**

给 `publish` 增加显式参数，只在「仅刷新个人候选」的路径保留重排：

```kotlin
private fun publish(engineSnapshot: EngineSnapshot, preserveModelRanking: Boolean = false)
...
modelRanked = preserveModelRanking && state.modelRanked,
```

`refreshPersonalization()` 与 `invalidateWordAssociations()` 传 `true`；引擎输入、翻页、选择、语言切换等传默认 `false`。另一种做法是让 `refreshPersonalization` 走「只替换候选列表、不动高亮与排序」的独立通道，避免整份 `EngineSnapshot` 重建。

---

### 2.3 Low

#### L-1 `AiContextSelection.append()` 可能写入负索引

| 项 | 内容 |
| --- | --- |
| 位置 | `app/src/main/kotlin/dev/zeroinput/ime/ai/AiContextSelection.kt:86` |
| 严重度 | **Low** |

```kotlin
replace(previous + listOf(messages.lastIndex - 1, messages.lastIndex), state.references)
```

`messages.size < 2` 时 `lastIndex - 1` 为负。`AiContextState` 目前不校验 `selectedHistory` 的范围，负值在 `filterIndexed { index in selected }` 中不命中，**不会崩溃**；但「继续对话自动带入最后一轮」在只有单条消息时静默失效。若后续给 `selectedHistory` 加上范围校验，这里会立即变成抛错点。

修复：

```kotlin
val pair = if (messages.size >= 2) listOf(messages.size - 2, messages.size - 1) else emptyList()
replace(previous + pair, state.references)
```

#### L-2 `StegoOptions.cycleWidth()` 的索引回绕缺省

| 项 | 内容 |
| --- | --- |
| 位置 | `app/src/main/kotlin/dev/zeroinput/ime/textstego/StegoOptions.kt:26` |
| 严重度 | **Low** |

```kotlin
MessageStage(cipher, widths[(widths.indexOf(stage.bits) + 1) % widths.size])
```

`stage.bits` 不在 `cipher.keyBits` 中时（旧配置残留、被外部改写、算法参数调整），`indexOf` 返回 `-1`，`(-1 + 1) % n == 0`，点击「切换密钥长度」看起来毫无反应而不是报错；`widths` 为空时 `% 0` 直接抛 `ArithmeticException`。

修复：

```kotlin
val next = widths.getOrNull(widths.indexOf(stage.bits) + 1)
    ?: widths.firstOrNull()
    ?: return@map stage        // 无可用长度时保持原值
MessageStage(cipher, next)
```

#### L-3 候选栏锚点在「全部按钮不可见」时被静默跳过

| 项 | 内容 |
| --- | --- |
| 位置 | `ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/CandidateStripView.kt:108-112` |
| 严重度 | **Low** |

```kotlin
val anchorIndex = buttons.indexOfFirst { it.isVisible && it.right > scroll.scrollX }
pendingAnchor = if (!changedInput)
    pendingAnchor ?: oldCandidates.getOrNull(anchorIndex)?.let { it.id to (buttons[anchorIndex].left - scroll.scrollX) }
    else null
```

`anchorIndex` 可能为 `-1`。此处**不会越界**：`getOrNull(-1)` 返回 null，`?.let` 不执行，`buttons[-1]` 永不求值（详见 §3-1）。但锚点保持 `null`，翻页后首项跳动。首次渲染或视图尚未布局（`left` 全为 0）时同样失效——`offset` 会等于 `-scrollX`，恢复结果无意义。

修复：显式判空并跳过未布局状态。

```kotlin
val anchorIndex = buttons.indexOfFirst { it.isVisible && it.right > scroll.scrollX }
pendingAnchor = if (!changedInput && anchorIndex >= 0 && buttons[anchorIndex].left > 0)
    pendingAnchor ?: oldCandidates.getOrNull(anchorIndex)?.let { it.id to (buttons[anchorIndex].left - scroll.scrollX) }
    else null
```

#### L-4 `SecureClipboardVault.metadata()` 的不可达 `checkNotNull`

| 项 | 内容 |
| --- | --- |
| 位置 | `user-data/src/main/kotlin/dev/zeroinput/userdata/SecureClipboardVault.kt:72`（另见 `:140`、`:158`） |
| 严重度 | **Low** |

```kotlin
SecureClipboardMetadata(draft.id, "", checkNotNull(draftEntries).length(draft.id), ...)
```

`draftEntries` 仅在公开构造器 `SecureClipboardVault(store, indexStore)` 下为 null，而该构造器下 `draftSummaries()` 返回空列表，循环体不执行——因此不可达。生产路径 `AppGraph.kt:123` 使用带 drafts 的构造器，恒非空；测试中另有 `assertThrows` 显式断言这条契约。

问题不在行为，而在**可读性**：三处 `checkNotNull` 让读者必须回溯构造器才能确认安全。建议统一为可选访问，使「无 drafts 即无草稿元数据」成为局部可证的事实：

```kotlin
draftEntries?.length(draft.id) ?: 0
```

#### L-5 原生资源销毁在队列满时可能被丢弃

| 项 | 内容 |
| --- | --- |
| 位置 | `app/src/main/kotlin/dev/zeroinput/ime/glide/GlideCoordinator.kt:99-108`；`app/src/main/kotlin/dev/zeroinput/ime/handwriting/HandwritingCoordinator.kt:165-174` |
| 严重度 | **Low** |

```kotlin
try {
    worker.execute { runCatching { decoder?.close() }; decoder = null }
} catch (_: RejectedExecutionException) { /* The owning worker has already stopped. */ }
worker.shutdown()
```

`invalidate()` 已先 `purge(worker)` 清空队列，正常情况下可入队。但队列容量为 1，若此刻恰有并发提交占满队列，`execute` 抛 `RejectedExecutionException` 被 `catch` 吞掉，`decoder`/`recognizer` **永不关闭**：native 句柄与内存泄漏，且注释给出的理由（worker 已停止）与真实原因（队列满）不符，误导后续维护。

修复：`catch` 分支中同步兜底关闭。

```kotlin
} catch (_: RejectedExecutionException) {
    runCatching { decoder?.close() }
    decoder = null
}
```

---

## 3. 已排除的疑似问题（复核撤回）

以下 5 处在阅读中一度标记为缺陷，逐一验证后确认**不成立**，不计入统计。记录在此以避免后续重复排查。

1. **`CandidateStripView.kt:111` 的 `buttons[anchorIndex]` 越界** —— `oldCandidates.getOrNull(-1)` 返回 null，`?.let` 不执行，`buttons[-1]` 永不求值。实际问题是锚点被跳过（L-3），不是崩溃。
2. **`KeyboardPanel.kt:328-340` 的 `keys[index++]` 与 `rows` 数量不一致** —— `geometry = rows.map { it.map(KeySpec::widthWeight) }` 完整编码了行数、每行键数与权重；任一项变化都会触发 `rebuild()` 重建 `keys`。`render()` 的索引遍历恒等。
3. **`PersonalCandidatePaging.publish()` 的递归重入无界** —— 递归分支内 `requested` 已归零，故 `wasFirstPage == true` 不再递归，深度恒为 2。代价是一次多余查询，不是栈溢出。
4. **`AndroidDeviceStage6Platform.kt:178`、`:214` 的 Cursor 泄漏** —— `use { if (!cursor.moveToFirst()) return failed(...) }` 中的 `return` 是经 inline `use` 的非局部返回，`finally { close() }` 仍会执行，Cursor 正常关闭。
5. **`SecureClipboardVault` 的 `checkNotNull` 导致 NPE** —— 见 L-4，生产构造器下恒非空，测试构造器下由断言显式覆盖，属有意的契约表达。

同时确认以下机制实现正确，未发现问题：

- **认证窗口**：`AuthenticationLifetime.consume` 先置 `consumed` 再判定，`SystemClock.elapsedRealtime` 基准，窗口 `minOf(maxAge, 30_000)`，不可重放。
- **原子写**：`EncryptedFileStore.writeIfActive` 在 `finishWrite` 后校验 `.new`/`.bak` 残留并抛错；`delete` 后校验三种路径均不存在。
- **信封格式**：`AesGcmEnvelope` 对 IV 长度（12..32）与剩余长度做 `require`，AAD 绑定文件名与版本。
- **安装器**：`LanguagePackInstaller` 要求 zip entry 集合与 manifest 完全一致、逐文件 sha256、`canonicalFile` + `startsWith(root + separator)` 防穿越、解压总量上限 256 MB；`RimeAssetInstaller` 的 `base-`/`shared-`/`user-` 世代目录与符号链接目标同样经过校验。
- **AI 端点**：`AiEndpoint.parse` 仅接受 https，拒绝 userInfo/query/fragment/`%`/`.`/`..`/重复斜杠，`fullRoute` 必须等于所选 protocol。
- **传输层**：`OpenAiCompatibleProvider` 的 `Operation` 用 `AtomicBoolean ended` 终结、`timer` 安装/撤销、`finally` 中断连接与连接池，`FutureTask` 内二次比对配置指纹。
- **线程池**：`BoundedExecutors.singleThread` 全部为 `ArrayBlockingQueue` + `AbortPolicy`，调用方普遍处理 `RejectedExecutionException`；`purge`/`cancelQueued` 与世代号 `AtomicLong` 配合，未发现无界队列。
- **设备代理**：`DeviceAgentCoordinator.Task` 的 `usedCallIds`、单调用限制、`finish()` 清空历史与附件、`broker.revokeTask`；无障碍快照对每个节点 `recycle`、`MAX_NODE_VISITS`、截图 `CountDownLatch` + `expired` 标志 + `HardwareBuffer` 关闭。
- **`Thread.sleep` 唯一出现处**：`DeviceAgentAccessibilityService.kt:285` 的 `wait` 动作。`perform` 运行于后台执行线程（`:528-529` 注释明确「Capture is bounded and runs off the service main thread，独立 executor 避免 broker worker 等待自身回调」），单次 `sleep` 上限 50 ms、总时长上限 5 s 且带中断处理，不构成 ANR 风险。

---

## 4. 统计汇总

| 严重程度 | 数量 | 条目 |
| --- | --- | --- |
| Critical | **0** | — |
| High | **1** | H-1 |
| Medium | **4** | M-1、M-2、M-3、M-4 |
| Low | **5** | L-1、L-2、L-3、L-4、L-5 |
| **合计** | **10** | |

按模块分布：

| 模块 | High | Medium | Low | 小计 |
| --- | --- | --- | --- | --- |
| `app` | 1 | 2 | 2 | 5 |
| `ime-core` | 0 | 1 | 0 | 1 |
| `ime-ui` | 0 | 2 | 1 | 3 |
| `user-data` | 0 | 0 | 1 | 1 |
| 其他 8 个模块 | 0 | 0 | 0 | 0 |

按类型分布：授权/安全默认值 1；界面交互与显示 3；异常处理与契约脆弱 1；并发与状态机 1；边界与索引 2；资源释放 1；可维护性 1。

---

## 5. 修复优先级建议

| 顺序 | 条目 | 理由 | 预估改动 |
| --- | --- | --- | --- |
| 1 | **H-1** | 授权模型 fail-open，属二道防线缺失；改动小、可测试 | 1 行 + 设置页首次写入 + 迁移分支 |
| 2 | **M-4** | 直接影响候选点击正确性，且跳变不可预测、难复现 | `publish` 增参数 + 2 处调用点 |
| 3 | **M-1** | 分段编辑是中文输入高频路径，选错段会被用户感知为「点不准」 | 重写 `setOffsets` + 补触摸定位测试 |
| 4 | **M-2** | 显示层丢字，影响观感但不影响数据 | 1 行条件判断 |
| 5 | **M-3** | 契约加固，防止未来重构引入崩溃 | 4 行 `as? Number` + 入口断言 |
| 6 | **L-1 ~ L-5** | 健壮性与可维护性，可随相关文件的下次改动一并处理 | 各 1-5 行 |

建议的验证方式（本轮未执行，交由后续提交时补齐）：

- H-1：全新安装后断言 `deviceAgentAllowedCategories` 为空；旧版本升级后断言收敛到推荐子集。
- M-1：对 `"ni hao · xiang"` 与 `pinyin_matched_reading` 两种 display 形态各写一组点击坐标 → 期望偏移的断言。
- M-2：构造 `composition.length > text.length` 的 `renderSearchDraft` 调用，断言渲染串包含全部 `text`。
- M-4：`applyModelWinner` 之后触发 `refreshPersonalization`，断言 `modelRanked` 仍为 `true`。

---

## 6. 审查局限

- **未执行构建与运行**：本报告为静态审查，未运行 Gradle、Lint、单元/仪器测试，未在真机或模拟器验证。所有结论均基于源码与契约推理；涉及 Android 平台行为的判断（如 `use` 的非局部返回、`getStringSet` 返回引用语义）按语言/平台语义推导，未做设备复现。
- **抽样模块**：`engine-dictionary`（仅 471 行，但含 QQ/Sogou/Rime 文本导入与滑行几何）、`model-scoring`（ONNX 封装与张量形状）、`app/settings` 各 `View` 的测量/布局细节、`expressions`、`app/ai/page` 仅做抽查，未逐行通读。这三块如需结论，建议另开专项审查。
- **未覆盖**：Gradle 构建脚本、Manifest、资源文件（`res/`）、ProGuard/R8 规则、`baseline-prof`。这些不在「代码逻辑」范围内，但可能影响运行时行为。
- **行号漂移**：若后续提交改动相关文件，§2 中的行号需重新定位；建议以「文件 + 函数名」为主要检索键。

---

## 附录 A：模块规模

| 模块 | 主源码文件 | 主源码行数 |
| --- | --- | --- |
| `app` | 210 | 28,768 |
| `ime-ui` | 72 | 9,021 |
| `user-data` | 23 | 2,839 |
| `engine-rime` | 25 | 2,662 |
| `ime-core` | 20 | 2,177 |
| `language-pack` | 23 | 2,126 |
| `ai-api` | 16 | 1,473 |
| `model-scoring` | 12 | 705 |
| `security` | 11 | 695 |
| `engine-dictionary` | 6 | 471 |
| `engine-api` | 13 | 476 |
| `engine-english` | 4 | 336 |
| **合计** | **435** | **51,749** |

含测试源码（`src/test`、`src/androidTest`）共 821 个 `.kt` 文件。统计范围已排除 `.gradle-user/` 与 `build/` 下的 Gradle 自身源码。
