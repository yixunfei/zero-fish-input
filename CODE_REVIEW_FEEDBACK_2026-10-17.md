# ZeroInput 代码审查反馈（第六轮 · 2026-10-17）

> 审查对象：`L:\zeroInput`，HEAD `2bc301b`（`fix: keep AI draft pinyin convertible instead of committing plain letters`）；工作树仅含未跟踪的 `.gradle-user/`、`.tmp-evidence/`。
> 审查性质：**只读**。除本文件外未修改任何源码、测试、Lint 规则、构建脚本或清单，未改动 Git 状态。
> 与既有文档的关系：本文作为**当前**问题清单，取代 `CODE_REVIEW_BUGS.md`（第五轮）。第五轮的多数结论在本轮复核中仍成立，但其中 P4（`onMeasure` 每键 binder 开销）已修（见 §5），因此第五轮的"8 项观察"清单不再准确。

---

## 0. 范围、方法与证据强度

- 规模：13 个 Gradle 模块，`app` 283 个源文件；全部自有源码约 5.6 万行 Kotlin（不含 `third_party/`、`build/`、`.cxx/`）。
- 方法：
  1. 按域逐文件通读（服务与生命周期、加密与用户数据、会话状态机、AI 与可选资源、引擎与语言包、UI、构建与门禁与脚本）。
  2. 每条发现回溯调用点与守卫，用 grep 确认**可达性**；不可达或已被守卫覆盖的一律剔除。
  3. 与 `AGENTS.md` §3–§9、`docs/architecture.md`、ADR 0002/0012/0015/0019/0021/0024 的约束逐条对照。
  4. 只收录能给出「文件 + 行号 + 代码证据 + 触发链」的项；纯风格、命名、注释类问题不计入。
- 证据强度：`confirmed-by-reading`＝控制流与默认值均已读码核对；`needs-runtime`＝结论取决于设备/平台/远端行为，静态只能证明条件成立。

---

## 1. 结论概览

| 级别 | 数量 | 编号 |
| --- | ---: | --- |
| 高 | 0 | — |
| 中 | 8 | M1–M8 |
| 低 | 20 | L1–L20 |
| 性能 / 观察 | 6 | P1–P6 |

数据点：12 个模块共 **706 个单元测试 / 0 failure / 2 skipped**，`privacyCheck`、`:app:lintDebug` 与 Debug 构建全部通过（详见 §6）。本轮未发现可导致崩溃、明文外泄、认证绕过或隐私边界失效的缺陷；问题集中在**一条构建可复现性断裂**、**三处输入正确性/可恢复性缺口**、**两处可选资源与语言包的内存/启动开销**、**两处热路径重复开销**，以及一组纵深防御与工程一致性问题。

---

## 2. 中优先级（用户可感知或阻断既有工作流）

### M1 干净检出（未执行 `bootstrap-rime.ps1`）时任何构建都会失败，与 README 承诺冲突

- **位置**：`engine-rime/build.gradle.kts:114-124`（`verifyOpenCcAssets`）、`:125-126`（对照 `prepareOpenCcAssets` 的 `onlyIf`）、`:209-210`（`preBuild` 依赖）
- **证据**：

```kotlin
// :114 —— 没有 onlyIf { hasNativeRime }
val verifyOpenCcAssets = tasks.register("verifyOpenCcAssets") {
    doLast {
        openCcHashes.forEach { (name, expected) ->
            val file = openCcDictionaryRoot.resolve(name)
            check(file.isFile) { "Pinned OpenCC dictionary is missing: $name" }
// :125 —— 同类任务有守卫
val prepareOpenCcAssets = tasks.register<Sync>("prepareOpenCcAssets") {
    onlyIf { hasNativeRime }
// :209
tasks.named("preBuild").configure { dependsOn(verifyOpenCcAssets, prepareOpenCcAssets, ...) }
```

- **触发路径**：干净克隆（`third_party/rime-deps/opencc/data/dictionary/*.txt` 不存在，`.gitignore` 排除整枝）→ `:app:assembleDebug`（不带 `-PrequireRime=true`）→ `preBuild` → `verifyOpenCcAssets` → `check(file.isFile)` 抛 `Pinned OpenCC dictionary is missing` → 构建中止。
- **影响**：`README.md:554` 明确承诺「未执行 `bootstrap-rime.ps1` 时可构建带降级拼音引擎的开发包」，该承诺在当前脚本下不成立；新贡献者的第一次构建必然失败，且错误信息指向 OpenCC 而非真正的先决条件。
- **置信度**：confirmed-by-reading（`hasNativeRime` 只被 `prepareOpenCcAssets` 与打包任务使用，`verifyOpenCcAssets` 未引用）。本机 `third_party/rime-deps` 存在，故本地构建通过；未通过删除依赖目录做破坏性复现。
- **建议**：为 `verifyOpenCcAssets` 增加 `onlyIf { hasNativeRime }`（与同类任务一致），或在任务内改用 `check(!hasNativeRime || file.isFile)`；同时让 `README` 的降级构建说明可执行。

### M2 词典编辑"未就绪"窗口内空格键被静默吞掉，同时引擎候选被清空

- **位置**：`ime-core/src/main/kotlin/dev/zeroinput/ime/core/InputSessionController.kt:173-174`、`PersonalCandidatePaging.kt:85-87`、`app/src/main/kotlin/dev/zeroinput/ime/personalization/QueuedPersonalizationStore.kt:72-73,87-91`、`DictionaryPersonalizationStore.kt:15`
- **证据**：

```kotlin
// InputSessionController.kt:173
InputCommand.Space -> {
    if (personalPaging.editsPending) return            // 不提交、不 fallback、不 publish
// PersonalCandidatePaging.kt:85
editsApplied = loaded.dictionaryEditsApplied
editsPending = editsApplied && !loaded.ready
if (editsPending) return native.copy(candidates = emptyList(), highlightedIndex = 0)
// QueuedPersonalizationStore.kt:87 —— 未就绪页把「编辑已应用」标成 true
private fun unavailablePage(...) = PersonalSuggestionPage(
    ready = false, ..., dictionaryEditsApplied = language == CHINESE &&
        (delegate as? DictionaryPersonalizationStore)?.requiresFiltering == true)
// DictionaryPersonalizationStore.kt:15 —— 默认 true，且 AppGraph 构造时未预置
@Volatile var requiresFiltering: Boolean = true
```

- **触发路径**：中文会话 → 首个 `Text` 键 → `handle` → `handleKey` → `apply` → `publish` → `personalPaging.publish` → `store.suggestionPage` → `QueuedPersonalizationStore` 在 `ready == false` 时返回上述 `unavailablePage`（`editsApplied=true`、`ready=false`）→ `editsPending=true` → 候选被清空；此时按空格命中 `:174` 直接 `return`。
- **影响**：
  1. 冷启动首个会话、以及每次 `invalidatePendingWrites()`（设置变化 `QueuedPersonalizationStore.kt:169-178`、词典变化 `AppGraph.kt:212/242`、清除数据）之后的窗口内，**空格键完全无效果**（不落字、也不提交候选），候选条同时为空；
  2. 若加密词库损坏或解密持续失败使 `ready` 永不回到 `true`（AGENTS §11.2 要求覆盖的场景），该失效会持续整个进程生命周期，只剩 `Enter` 与点选可用；
  3. 英文会话不受影响（`language == CHINESE` 条件）。
- **置信度**：confirmed-by-reading（四个默认值/分支均已核对）；"永久失效"分支 needs-runtime。
- **建议**：把「未就绪」与「编辑已应用」拆成两个状态——未就绪时返回 `dictionaryEditsApplied = false`（只影响个性化候选，不清空引擎候选、不吞键）；或在 `editsPending` 分支让空格退化为 `handleEnter`/engine `Space`，仅抑制个性化行。

### M3 Anthropic 协议 + 深度思考必然返回 400（`max_tokens` 与 `budget_tokens` 相等）

- **位置**：`app/src/main/kotlin/dev/zeroinput/ime/ai/AiRequestEncoder.kt:38-42`、`:91-95`；调用方 `AiWorkbenchController.kt:206-208`
- **证据**：

```kotlin
AiProtocol.ANTHROPIC_MESSAGES -> {
    put("max_tokens", request.outputTokenLimit ?: 4096)          // 工作台从不设置该字段
    request.reasoningMode.anthropicBudget()?.let { budget -> put("thinking", JSONObject()
        .put("type", "enabled").put("budget_tokens", budget)) }   // DEEP = 4096
}
```

- **触发路径**：AI 面板切到「深度思考」→ `ZeroInputService.onAiReasoningModeChanged` → `AiWorkbenchController.setReasoningMode(DEEP)` → `submit` 构造 `AiRequest`（未设 `outputTokenLimit`，`ai-api/.../AiModels.kt:57` 默认 `null`）→ `OpenAiCompatibleProvider.stream` → 编码出 `max_tokens=4096` 与 `thinking.budget_tokens=4096`。
- **影响**：Anthropic Messages 要求 `budget_tokens` **严格小于** `max_tokens`；两者相等必返回 `400 invalid_request_error`。即「Anthropic 协议 + 深度思考」组合 100% 失败，用户只看到通用 HTTP 失败（`AiNetworkFailure.REQUEST`）。`FAST`（1024）与 `DEFAULT` 不受影响。`AiProtocolCodecTest.kt:34` 只断言编码出的 `budget_tokens=4096`，未与 `max_tokens` 交叉校验，因此单测不会暴露。
- **置信度**：confirmed-by-reading（编码值可静态确定）；厂商裁决 needs-runtime。
- **建议**：`DEEP` 预算改小（如 2048）或在设置 `thinking` 时把 `max_tokens` 提升到 `budget + 1024`；补一条「`budget_tokens < max_tokens`」的编码不变量测试。

### M4 引擎运行期失败后既无失败状态，也没有会话内恢复入口

- **位置**：`ime-core/.../InputSessionController.kt:158-166`、`:213-219`、`:903-913`；对照 `app/.../ZeroInputService.kt:1709-1725`、`:1114-1125`、`:1471`
- **证据**：

```kotlin
// InputSessionController.kt:158 —— 失败后 engine == null，本会话后续所有键走字面提交
val activeEngine = engine
if (activeEngine == null) { handleEditorFallback(command); return }
...
private fun recoverFromEngineFailure(failedEngine: InputEngine) {
    if (engine === failedEngine) closeEngine() else closeSafely(failedEngine)
    ...
    publish(EngineSnapshot.Empty)          // 没有任何「失败」标记进入状态
```

```kotlin
// ZeroInputService.kt:1712 —— 状态推导不看 engineDescriptor 是否为 null
val status = when {
    state == null || !state.privacy.suggestionsAllowed || ... -> HIDDEN
    sessionChineseOptions != configuredChineseOptions -> PENDING_CONFIGURATION
    graph.rime.runtime.state == FAILED || unavailableEngineWarmupContext != null -> FAILED
    session.matchesWarmup(installedEngineWarmupContext, ...) -> READY   // 仍判为「就绪」
    else -> PREPARING
}
```

- **触发路径**：`handle` 内的引擎调用抛异常 → `catch (_: Throwable)` → `recoverFromEngineFailure` 关闭引擎 → 之后每个键走 `handleEditorFallback`（直接 `commitText` 字面字符）；`retryEngineWarmupIfIdle` 只在 `engineWarmupRetry` 为真时重试，而该标志仅由"预热结果移交失败"设置（`ZeroInputService.kt:1527` 附近），`scheduleEngineWarmup` 又会因 `installedEngineWarmupContext` 仍匹配而在 `:1471` 早退。
- **影响**：中文会话中途退化成"字面拼音输入器"，而 UI 仍显示"就绪"，除切换编辑器/重建会话外没有恢复路径。个人化写入失败尚有 `learningFailureCount` 可观测，引擎失败没有任何等价状态位——与 AGENTS §5.3「引擎不可用…必须显式进入失败状态并使用受控降级路径」不符。
- **置信度**：confirmed-by-reading（控制流与 UI 推导均已核对）；JNI 是否真会抛 JVM 异常 needs-runtime。
- **建议**：在 `InputSessionState` 增加 `engineFailure` 标记（或复用 `engineDescriptor == null && suggestionsAllowed`），让 `renderEngineStatus` 落到 `FAILED` 并提供 `retryEngine()`；同时允许 `scheduleEngineWarmup` 在引擎缺失时忽略 `installed…` 匹配。

### M5 安全剪贴板正文与索引是两次独立落盘，且提交后仍有取消检查：报"失败"但数据已保存

- **位置**：`user-data/src/main/kotlin/dev/zeroinput/userdata/SecureClipboardVault.kt:113-119`（`add`）、`:130-136`（`remove`）、`:342-365`（`persist`）、`:367-393`（`persistIndex`）、`:260`
- **证据**：

```kotlin
entries += entry
checkOperationActive(expectedGeneration, isActive)
persist(entries) { checkOperationActive(expectedGeneration, isActive) }   // 正文已提交，indexNeedsRepair=true
checkOperationActive(expectedGeneration, isActive)                       // 提交之后仍可抛出
persistIndex(entries)                                                    // 第二次独立写入，可失败
```

- **触发路径**：`SecureClipboardManagerActivity.addItem` 或 `ClipboardImportActivity` 保存 → `vault.add` → 正文写成功 → 第 116 行的 `isActive()` 已变假（30 秒草稿到期，或会话被 `discard()` 关闭）→ 抛 `CancellationException`；或 `persistIndex` 因磁盘/Keystore 失败抛 `IOException`。
- **影响**：条目事实上已加密保存，界面却提示失败 → 用户重试产生重复条目；`indexNeedsRepair` 被 `persist` 置真后无法回滚，`summaries()`（`:260`）持续抛 `IOException`，键盘侧安全剪贴板面板显示"加载失败"，直到用户在管理页重新认证触发 `metadata()` 修复。`remove()` 同链路失败后，"已删除"却报错，管理页本地列表不更新。
- **置信度**：confirmed-by-reading；`SecureClipboardIndexTest:59-71` 表明"索引待修复"半状态是被测试认可的既有行为。
- **建议**：把"正文 + 索引"定义为一个提交单元（索引写失败时回滚正文，或把索引降级为可重建缓存并立即重建）；把"已提交"与"后续取消"分离——提交成功后不再抛取消异常，改为返回结果并附带 `indexRepaired=false`。

### M6 按键热路径每键重复两次 header 刷新与两次选项渲染

- **位置**：`ime-ui/.../ZeroInputView.kt:313`、`:336-341`、`:951-956`、`:466`；`app/.../ZeroInputService.kt:1057`、`:1464-1466`、`:1691-1693`、`:429-440`
- **证据**：

```kotlin
// app：每次按键都会走到这里
handleKeyboardAction -> maybeReloadLanguagePack()            // :1057
  -> scheduleEngineWarmup(session)                            // :1464（无语言包时 :1671-1674 无条件执行）
    -> reconcileChineseOptions()                              // :1466
      -> inputView.renderChineseOptions(configured)           // :1693（在相等判断之前，无守卫）
// 同一按键状态发布
onStateChanged -> renderSession(state)                        // :430
                    -> refreshHeader()                        // ZeroInputView:313
                -> renderEngineStatus()                       // :438
                    -> refreshHeader() + renderChineseOptions()  // ZeroInputView:340 / :466
```

```kotlin
// ZeroInputView.kt:954 —— 无 id 判等，每次都重建 Drawable 并 requestLayout
expansionButton.setImageResource(if (mode == PanelMode.CANDIDATES)
    R.drawable.ic_handwriting_fullscreen else R.drawable.ic_panel_expand)
```

- **影响**：默认中文、无语言包的稳态下，**每键固定 2 次 `refreshHeader`（约 15 处 View 属性写入）+ 2 次 `renderChineseOptions`（含 `getString`、`ViewCompat.setStateDescription`、`obtainStyledAttributes` 取色）**。按 8–10 键/秒估算，约每秒 2 次 Drawable 重建、20 次左右短字符串分配、2 次主题属性解析与 2 次 IME 根 `requestLayout`。对照：同一方法内 `scriptButton.text` 已有 `if (scriptButton.text != label)` 判等（`ZeroInputView.kt:469`），`KeyboardPanel` 的方案 setter 也有 `if (doublePinyinScheme == value) return`（`KeyboardPanel.kt:264`）——说明这里的无条件写入（含 `fuzzyButton` 的三次属性写与一次取色）是遗漏而非设计。
- **置信度**：confirmed-by-reading（调用链与次数逐跳核对）；毫秒代价 needs-runtime（建议 Perfetto 对比）。
- **建议**：`reconcileChineseOptions` 把渲染移到相等判断之后；`ZeroInputView` 对 `renderChineseOptions`/`refreshHeader` 加末值缓存（`if (renderedOptions == options) return`，`expansionButton` 记录当前 drawable id）；`onStateChanged` 内合并为一次状态渲染。

### M7 可选语法模型（380 MiB）在唯一的引擎 worker 上每次初始化全量重算 SHA-256

- **位置**：`language-pack/.../PublicResourceStore.kt:42-59`（`acquire`）、`:168-169`（`valid`）；`engine-rime/.../RimeRuntime.kt:134`、`:45`、`:59`
- **证据**：

```kotlin
// PublicResourceStore.kt:168 —— 每次 acquire 都对整文件重算哈希，无持久化票据
private fun valid(file: File, spec: ResourceFileSpec): Boolean = file.isFile && file.length() == spec.bytes &&
    file.inputStream().use { digest(it) } == spec.sha256
```

`tools/resources/catalog.json` 中 `wanxiang-lts` 的 `rime/wanxiang-lts-zh-hans.gram` 声明为 **398,309,420 B**（380 MiB）。

- **触发路径**：`AppGraph.init` → `engineExecutor.execute { rime.warmUp() }` → `RimeRuntime.initialize` → `resources?.acquire("wanxiang-lts")` → `valid()` 全量读盘哈希；词典发布或启用/禁用后 `dictionariesChanged()` → `prepareDictionariesIfIdle` → `resolveAssetDirectories()`（第 1 次哈希）→ 资产身份变化 → `close()` + `initialize()`（第 2 次哈希）。
- **影响**：冷启动以及每次词库增删/替换都要对 380 MiB 做 1–2 次全量读 + SHA-256（单次耗时取决于设备存储与 SHA 实现，未实测），而这段时间占用的是**唯一**的 `engineExecutor`（`queueCapacity=2`、`AbortPolicy`）。在此期间 `EngineWarmupCoordinator` 的请求排队乃至被拒（`Unavailable` → 状态 `FAILED`），用户停留在约 50 词的内置降级引擎上打字。由于没有"已校验"票据持久化，进程每次重启都会重算。同一把 `monitor` 锁还覆盖 `list/enable/install`（`PublicResourceStore.kt:32-40,110-125`），因此哈希期间资源管理页的"检查更新/启用"也会一起排队。
- **置信度**：confirmed-by-reading（调用链与文件大小均来自仓库内数据）；耗时与拒绝率 needs-runtime。
- **建议**：把校验结果作为票据持久化（`(path, size, mtime, sha256)` 或内容寻址 blob 的"已验证"标记落在 `noBackupFilesDir`）；词库变动只重建与词库相关的身份，不重算无关 grammar；或把资源校验移到独立 worker，避免阻塞引擎预热。

### M8 语言包前缀索引的**桶数量**不受条目/字节上限约束，可被一个合法包放大到 OOM

- **位置**：`language-pack/.../LanguagePackEngine.kt:45-48`（每个引擎实例重建）、`:132-144`（`buildPrefixIndex`）、`:161-163`（`MAX_ENTRIES=50_000`、单文件 16 MiB、总量 32 MiB）、`:282`（shortcut ≤64）
- **证据**：

```kotlin
for (length in 1..shortcut.length) {
    val bucket = index.getOrPut(shortcut.substring(0, length)) { LinkedHashSet() }
    for (value in values) { if (bucket.size >= MAX_CANDIDATES) break; bucket += value }
}
```

- **影响**：`MAX_CANDIDATES=8` 只限制**桶内元素**，不限制桶的个数。50 000 条、每条 64 字符的 shortcut（约 4–5 MB 文本，完全在 16 MiB/32 MiB 闸门内）可生成上界 3.2 M 个互不相同的 `String` 键 + 同等数量的 `HashMap` 节点与 `LinkedHashSet`，粗估 0.2–0.5 GB —— IME 进程 OOM 会连带用户正在输入的应用。索引还在每次创建引擎实例（会话/语言切换）时重建。
- **备注**：第五轮把该项记为"受 MAX_ENTRIES/字节上限约束的有界 P1"；本轮补上"桶数量"这一维度后，它是**未被字节上限约束**的内存放大，故从观察项升级为缺陷。
- **置信度**：confirmed-by-reading（上限常量与构造方式已核对）；实际 OOM 阈值 needs-runtime。
- **建议**：索引在 `LanguagePackEngineFactory` 上建一次并共享；参与索引的前缀长度限制为 4–8；对桶总数设硬上限，超限整包失败关闭（fail-closed）。

---

## 3. 低优先级（正确性缺口、纵深防御与工程一致性）

### L1 九键组合经"AI 草稿交接"后变成字面数字，未被转换为候选

- **位置**：`app/.../input/LocalDraftInput.kt:139-150`；对照 `ime-core/.../InputSessionController.kt:550-551`
- **证据**：

```kotlin
// LocalDraftInput.kt:141 —— 只接受 a-z 与 '
if (value.isEmpty() || value.any { it !in 'a'..'z' && it != '\'' }) return appendImported(value)
// InputSessionController.kt:550 —— 权威的交接字符集还包含九键数字与分号
fun isHandoffPinyinCharacter(value: Char): Boolean =
    value in 'a'..'z' || value in '2'..'9' || value == '\'' || value == ';'
```

- **路径**：九键布局下输入中（`rawInput` 为数字串，`NineKeyReadings.numericSpan` 依赖它）→ 打开 AI 面板 → `handoffComposition` 把 `rawInput` 交给 `aiDraft.appendPreedit` → 因含数字被判为"非拼音"，走 `appendImported` → 草稿里出现字面 `946`，不再转换。
- **影响**：正是最近一次提交（`2bc301b`）想修掉的"未转换字母进草稿"问题在九键路径上仍然存在，只是表现从字母变成数字；用户必须手动重打。
- **置信度**：confirmed-by-reading。
- **建议**：把手写字符集抽成 `engine-api` 的单一判定（例如 `PinyinHandoff.isCodePoint`），供 `InputSessionController.canRestorePreparedComposition` 与 `LocalDraftInput.appendPreedit` 共用。

### L2 词库写入把"容量超限"和"操作取消"都登记为 `WRITE_FAILED`，并弹出"写入失败"

- **位置**：`user-data/.../UserLexiconRepository.kt:294-303`
- **证据**：

```kotlin
try {
    if (bytes.size > UserLexiconFormat.MAX_IMPORT_BYTES) capacityExceeded()  // 纯本地判断
    ensureCurrent(expected)                                                  // 清除/失效时抛
    store.write(bytes)
} catch (error: Throwable) {
    writeFailure = UserDictionaryFailure.WRITE_FAILED                        // 两种都归为 I/O 失败
    writeFailureListeners.forEach { ... }
```

- **路径**：① 词库序列化后超 `MAX_IMPORT_BYTES`；② 与 `clear()`（`generation` 在锁外自增，`:177`）并发时 `ensureCurrent` 抛错。两者都触发 `MainActivity.kt:83-89` 的 `user_dictionary_write_failed` Toast。
- **影响**：把"达到上限"和"操作被新的隐私/清除决定取消"报成存储故障；`lastWriteFailure()` 会残留到下一次成功写入，误导用户以为数据可能丢失。
- **置信度**：confirmed-by-reading。
- **建议**：`capacityExceeded` 与 `ensureCurrent` 失败不进入 `writeFailure` 通道；补 `UserDictionaryFailure.CAPACITY` / `CANCELLED` 语义，或用独立异常类型分流。

### L3 词库与 emoji 的历史数据把整份明文物化成不可擦除的 `String`

- **位置**：`user-data/.../UserLexiconFormat.kt:25-45`、`UserLexiconRepository.kt:294`、`EmojiHistoryFormat.kt:31-40`；对照 `JsonPlaintext.kt:10-32`
- **证据**：

```kotlin
val characters = decoder.decode(ByteBuffer.wrap(bytes))
try { return JsonReader(StringReader(characters.toString().removePrefix("\uFEFF"))).use(::readDocument) }
finally { if (characters.hasArray()) characters.array().fill('\u0000') }   // CharBuffer 清零，
                                                                          // 但 toString() 的 String 副本无法擦除
```

- **影响**：`JsonPlaintext.kt` 里的 `withJsonReader(bytes) { … }`（`:10-32`）正是为"不把整份文档物化为不可擦除 String"而存在，安全剪贴板正文/索引（`SecureClipboardVault.kt:188,290`）、AI 配置（`AiConfigurationRepository.kt:100,114`）、AI 会话（`AiConversationRepository.kt:83`）都走这条路径；唯独明文体量最大的词库（`MAX_IMPORT_BYTES` = 5 MiB）与 emoji 历史例外，`bytes.fill(0)` 的擦除被这份 GC 前不消失的副本架空。属 AGENTS §4「解密出的明文字节数组、字符缓冲区和临时副本应缩短生命周期」的实现不一致。
- **置信度**：confirmed-by-reading。
- **建议**：让 `UserLexiconFormat`/`EmojiHistoryFormat` 复用 `JsonPlaintext` 的流式读取；序列化侧避免 `JSONObject.toString()` 中间 String（或接受并以注释记录该边界）。

### L4 `hasKey()` 吞掉 Keystore 异常返回 `false`，清除数据后导出密钥可能残留

- **位置**：`security/.../AesGcmKeyStore.kt:27-28`；调用点 `UserLexiconRepository.kt:187-191`、`PersonalExpressionRepository.kt:121-125`
- **证据**：`fun hasKey(): Boolean = runCatching { loadKeyStore().containsAlias(alias) }.getOrDefault(false)`
- **影响**：provider 瞬时故障时被判为"无密钥"→ 跳过 `deleteKey()`，`USER_DATA_EXPORT` / `EXPRESSIONS_EXPORT` 别名在"清除全部用户数据"后仍然存在，与 §4「清除用户数据时应删除对应密文及专用 Keystore 密钥」不符。导出密文在用户自选文件中，实际暴露面小。
- **置信度**：confirmed-by-reading（前提是 Keystore 抛异常）。
- **建议**：`deleteKey()` 直接幂等调用（`containsAlias` 判断放到实现内部并让异常上抛），或让 `hasKey()` 区分"不存在"与"查询失败"。

### L5 AI 会话数达上限时静默淘汰最旧一条，与同文件字节上限的显式失败口径不一致

- **位置**：`user-data/.../AiConversationRepository.kt:40-47`；上限 `ai-api/.../AiModels.kt:158`（`MAX_CONVERSATIONS = 32`）
- **证据**：`.sortedByDescending { it.updatedAtEpochMillis }.take(AiLimits.MAX_CONVERSATIONS)`
- **影响**：保存第 33 条会话时最旧一条被无提示删除；同一文件的字节上限（`persist` 超 4 MiB）却抛"storage is full"。
- **置信度**：confirmed-by-reading。
- **建议**：超限时返回可判别结果（`STORAGE_FULL`）或先提示用户清理；两条配额口径保持一致。

### L6 AI 提交把 `AiGenerationPolicy` 全量硬编码为放行，ai-api 的策略层在真实链路上是空操作

- **位置**：`app/.../ai/AiWorkbenchController.kt:245`；策略定义 `ai-api/.../AiModels.kt:132-147`
- **证据**：`handle = coordinator.submit(request, AiGenerationPolicy(true, true, false), sink::offer)`
- **影响**：`sensitiveEditor` 恒为 `false`、`personalizationAllowed/suggestionsAllowed` 取默认 `true`，因此 ai-api 中"密码/PIN/邮箱/URI/关闭学习 → 拒绝"的独立性守卫不会被触发；当前所有调用点都被 `ZeroInputService.aiAllowed()` 正确挡住，**不构成现存越权**，但任何新增调用点都会直接继承"无复核"。
- **置信度**：confirmed-by-reading。
- **建议**：从 `SessionPrivacy` 构造真实策略（`sensitiveEditor = privacy.isSensitive` 等），把 UI 侧判断降级为"提前拒绝"，而不是唯一防线。

### L7 导入内容先被 `take()` 取走再校验，预算不足时内容直接丢失且需重新导入

- **位置**：`app/.../ZeroInputService.kt:1002-1018`；预算 `ai-api/.../AiContext.kt:17,25`
- **证据**：

```kotlin
val content = graph.aiContentInbox.take() ?: return renderAiContent()   // 先销毁 inbox 条目
...
if (references != null && attachmentsFit && aiWorkbench.addReferences(...)) { ... }
else { content.close(); showAiContextRejected() }                       // 内容已清零丢弃
```

- **路径**：`AiComposeActivity` 的输入上限是 `MAX_INPUT_CHARS = 16384`，而上下文总预算同为 16384；用户在同一会话中已勾选任何历史消息后，再导入接近上限的长文本必然超出 `addReferences` 的校验。
- **影响**：提示"引用被拒绝"，且用户无法直接重试，必须重新打开导入页重填。
- **置信度**：confirmed-by-reading（两个上限相等是根因）。
- **建议**：先做预算预检再 `take()`，或在拒绝时把内容退回 inbox（保留 TTL 内可重试）。

### L8 深层嵌套 JSON 触发 `StackOverflowError` 时，AI 请求不会收到任何终态事件

- **位置**：`app/.../ai/AiResponseReader.kt:41-60`；`OpenAiCompatibleProvider.kt:102-105`、`:199`
- **证据**：

```kotlin
return try {
    val tokens = JSONTokener(text.toString())     // 递归下降，2 MiB 的深层嵌套足以爆栈
    ...
} catch (error: AiProviderError) { throw error
} catch (_: Exception) { throw AiProviderError.Response("AI response is invalid") }   // Error 不被捕获
```

```kotlin
} catch (error: Exception) { if (error is InterruptedException || ...) operation.cancel() else operation.fail(...) }
finally { operation.finish() }                    // finish() 关闭超时计时器；ended 仍为 false
```

- **影响**：`StackOverflowError` 越过 `catch (Exception)`，`finally` 关闭了 deadline 计时器，`FutureTask` 吞掉 error → 既没有 `Failed` 也没有 `Cancelled`，UI 进度会一直停在"生成中"，直到用户手动取消。触发需要端点返回恶意/异常的超深 JSON（端点由用户自填，故属健壮性而非越权）。
- **置信度**：confirmed-by-reading（异常层级与计时器关闭顺序可静态确定）；爆栈阈值 needs-runtime。
- **建议**：在 `launch` 的 `catch` 增加 `Throwable` 兜底（或至少 `Error`），确保任何逃逸异常都走 `fail()`；`finish()` 内若 `!ended` 则补发失败事件。

### L9 第三方词典目录行数无上限，异常响应会在主线程建数千个 View

- **位置**：`app/.../dictionaries/DictionaryCatalog.kt:71-102`；消费侧 `DictionaryManagerActivity.kt:142-161`
- **证据**：网站解析有 `require(output.size <= 1024)`，而 `githubTree`/`zhwiki` 直接 `(0 until entries.length()).map { ... }`，仅把响应体限制在 4 MiB。
- **影响**：上游仓库被投毒或异常膨胀时，点"浏览"会在主线程 `removeAllViews()` 后逐条 `addView`，造成长时间卡顿/ANR；需三次显式点击且不触及私有数据，故列低。
- **置信度**：confirmed-by-reading。
- **建议**：四个目录来源统一 `require(size <= 1024)`，并在 UI 侧分页。

### L10 `enabled.json` 在启动时损坏后，任一次开关操作会**重新启用此前被禁用的所有语言包**

- **位置**：`language-pack/.../LanguagePackInstaller.kt:301-316`（`readEnabledState`）、`:318-331`（`writeEnabledState`）、`:97-99`（`setEnabled`）、`:178`、`:268`（默认值 `?: !enabledStateInvalid`）
- **证据**：

```kotlin
} catch (_: Exception) { enabledStateInvalid = true; enabledStateCache?.toMutableMap() ?: mutableMapOf() }
...
val enabled = readEnabledState()[key(manifest.id, manifest.version)] ?: !enabledStateInvalid   // 读失败 ⇒ 视为启用
// writeEnabledState 末尾
enabledStateInvalid = false                                                                    // 一次写入即把标志复位
```

- **路径**：`enabled.json` 在进程启动时已损坏 → 首次 `readEnabledState()` 走 catch（缓存为空）→ 用户在设置页切换任一语言包 → `setEnabled` 用空 map + 一个新键回写 → `writeEnabledState` 把 `enabledStateInvalid` 复位为 `false` → 之后 `readInstalled` 对**其余所有包**取默认 `!false = true`。
- **影响**：一次点击把用户此前禁用的语言包全部重新启用（fail-open），并被持久化；同时损坏文件被部分状态不可逆覆盖。另"文件缺失 ⇒ 默认启用"与"文件损坏 ⇒ 默认禁用"两种默认口径本身不一致。
- **置信度**：confirmed-by-reading（可直接用写坏 `enabled.json` 的 instrumented 测试复现）。
- **建议**：损坏时拒绝用部分状态回写（重建或让 `setEnabled` 失败并要求重扫）；把默认口径统一为"未知即禁用"。

### L11 超时槽位耗尽被归类为网络/排队失败，与真实网络错误不可区分

- **位置**：`app/.../ai/OpenAiCompatibleProvider.kt:109-118`；`AiRequestResources.kt:32-46`
- **证据**：`AiRequestTimeouts.schedule` 的 `tryAcquire` 失败抛 `RejectedExecutionException`，被 `launch` 的 catch 归为 `AiProviderError.Network("AI request could not be queued")`。
- **影响**：最多 4 个并发 deadline（工作台 + 测试 + 模型发现共用槽位）；耗尽时用户看到"网络失败"，无法区分"请先结束当前测试"。
- **置信度**：confirmed-by-reading（触发概率低）。
- **建议**：新增独立 `AiNetworkFailure.CAPACITY` 或改用 `AiProviderError.Configuration`。

### L12 编辑器连接调用缺少异常保护：一次失败后的"恢复路径"可能把异常抛到 IME 主线程

- **位置**：`ime-core/.../InputSessionController.kt:154-166`、`:213-219`；适配器 `app/.../input/AndroidEditorConnection.kt:27-38,61-73,122-136`
- **证据**：

```kotlin
if (privacy.isSensitive) { handleSensitive(command); return }   // 无 try，直接进 handleEditorFallback
...
} catch (_: Throwable) {
    recoverFromEngineFailure(activeEngine)
    handleEditorFallback(command)                               // 用同样的适配器再试一次
}
```

- **影响**：`AndroidEditorConnection` 的 `commitText`/`setComposingText`/`deleteBeforeCursor` 直接跨 Binder 且无 `runCatching`；一旦编辑器侧抛 `RuntimeException`（进程消亡等），`catch` 内重试同样失败，异常从 `handle()` 逃逸到 `ZeroInputService.handleKeyboardAction`（无外层捕获）→ 进程崩溃而非降级。`publish` 已对 `onStateChanged` 使用 `runCatching`，说明同一原则已知但未施加到连接层。
- **置信度**：confirmed-by-reading（控制流）；平台是否真抛 needs-runtime。
- **建议**：把 `EditorConnection` 的每个实现方法包一层 `runCatching` 并返回失败语义（`commitText` 已有 `Boolean`），或在 `handle()` 外层兜底。

### L13 用可见语言标签当语言语义：语言包会话失去 `'` 分节符，且标签是硬编码字符串

- **位置**：`ime-ui/.../KeyboardPanel.kt:480`、`ZeroInputView.kt:316-327`、`KeyboardLayouts.kt:36`
- **证据**：

```kotlin
val delimiter = action.opening == "'" && composing && languageLabel == "中"   // 语言包会话里标签是「包」
// ZeroInputView.kt:317
val label = when { currentPack != null -> "包"; currentLanguage == CHINESE -> "中"; else -> "En" }
```

- **影响**：语言包会话（`currentPack != null`）中标签为"包"，`delimiter` 恒 `false` → 走 `PairedText`，即**提交未完成组合并插入 `''`**（正是该行注释警告的后果）；`LanguagePackEngine` 的 `isShortcutCharacter`/`looksLikeShortcut` 接受 `'`，说明分节符对语言包有意义。同处还有 `"中"/"En"/"包"` 硬编码可见字符串（违反 AGENTS §7「可见字符串放入资源」），英文语言包会话的逗号键还会显示全角「，」。
- **置信度**：confirmed-by-reading。
- **建议**：把"当前会话是否为中文/是否支持分节符"作为显式参数（来自 `InputSessionState`），显示文本单独从资源取。

### L14 长按备选浮层不随视图 detach 关闭

- **位置**：`ime-ui/.../KeyboardPanel.kt:383-413`、`:493`
- **证据**：`showAlternatives` 用局部 `var popup` 创建并 `showAsDropDown`，面板没有保存引用；`KeyboardPanel.onDetachedFromWindow` 只调 `cancelPendingGestures()`。
- **影响**：长按符号键后立刻切应用/收键盘时，浮层没有显式关闭路径（对照 `KeyboardLayoutHost.onDetachedFromWindow`、`AiWorkbenchPanelView.onDetachedFromWindow` 都做了 `dismiss()`）。是否需要平台自动回收属运行期行为。
- **置信度**：缺少关闭路径 confirmed-by-reading；残留表现 needs-runtime。
- **建议**：把 `popup` 提升为字段，在 `onDetachedFromWindow`/`cancelPendingGestures` 中 `dismiss()`。

### L15 破坏性设置操作的结果在 Activity 销毁时被静默丢弃

- **位置**：`app/.../settings/MainActivity.kt:116-130`、`:257`、`:273`；`SettingsTaskRunner.kt:29-39`
- **证据**：

```kotlin
operations.execute({ graph.clearPersonalizationData() }) { Toast... }   // 成功回调
override fun onDestroy() { operations.close(); ...; worker.shutdownNow() }
private fun deliver(callback: () -> Unit) { if (closed.get()) return; post { ... } }   // 关闭后静默丢弃
```

- **影响**：用户点"清除个人数据/清除安全剪贴板"后立即旋转屏幕或返回 → `onDestroy` 先置 `closed`，成功与失败回调都被丢弃；界面不再 `render()`，用户无法确认操作是否完成。`clearPersonalizationData()` 内部是三步独立删除并聚合失败，部分删除时更缺少提示。
- **置信度**：confirmed-by-reading。
- **建议**：破坏性操作改投应用级执行器（结果不依赖页面存活），或在销毁时以通知/`Toast` 之外的方式记录结果；至少区分"已受理"与"已完成"。

### L16 安全剪贴板管理页在"取消额外认证"后既不 finish 也不清空已持有元数据

- **位置**：`app/.../settings/SecureClipboardManagerActivity.kt:63-71`、`:269-301`
- **证据**：

```kotlin
override fun onStop() {
    privateDialog?.dismiss(); screen.clearMetadata()
    if (!authenticationInProgress) { metadata = emptyList(); if (!isChangingConfigurations) finish() }
}
// 认证取消（cancelClosesScreen = false，即新增/删除路径）→ 渲染列表但不再 finish
if (grant != null) onGranted(grant) else { onCancelled(); setBusy(false)
    if (cancelClosesScreen) finish() else { if (secureClipboardEnabled) screen.renderEntries(metadata) ... } }
```

- **影响**：在"已认证看到列表 → 点新增/删除 → 系统认证框 → 取消"之后，页面停留在 stopped 状态、既不 finishing 也不 destroyed，`metadata`（条目 id 与标签）长期驻留并重新渲染；与 `onStop` 的"后台即清空"意图不一致。正文不在该页，且 `FLAG_SECURE` 已设，故列低。
- **置信度**：confirmed-by-reading。
- **建议**：取消分支回到"页面即关闭"或统一用 `onStop` 的清理路径（清 `metadata` + `finish()`）。

### L17 有界队列拒绝在两个收尾路径被静默吞掉

- **位置**：`app/.../ZeroInputService.kt:1393-1441`（`.getOrNull()`）、`app/.../clipboardguard/ClipboardGuardRuntime.kt:278-287`（`enqueue` 返回值未检查）
- **证据**：

```kotlin
localPanelTask = runCatching { localDataExecutor.submit { ... } }.getOrNull()   // 拒绝 → null，无重试
// ClipboardGuardRuntime
enqueue { detachPlatform(); notifications.cancel() }; worker.shutdown()          // 队列满则 detach 不执行
```

- **影响**：表情/最近 emoji/安全剪贴板列表可能停留在旧快照且没有重试；`ClipboardGuardRuntime.close()` 在队列满时不会注销 `ContentObserver` 与剪贴板监听（当前唯一调用点是 `AppGraph.close()`，实机不执行，故风险低）。
- **置信度**：confirmed-by-reading。
- **建议**：面板刷新失败时保留 `localPanelRevision` 供下次交互重排；`close()` 的收尾动作改为在 `closed` 后同步执行（不经队列）。

### L18 `privacyCheck` 对合并清单的覆盖远小于 `AGENTS.md` §11.1 的表述

- **位置**：`build.gradle.kts:134-222`
- **证据**：任务只校验四件事——无障碍 service 数量与权限、`PageReferenceService` 的 `exported`/`permission`、`uses-permission` 白名单、两份 `page_reference_service.xml`。新增一个 `exported="true"` 的 Activity、把 `allowBackup` 改成 `true`、或打开 `debuggable`，**都能在门禁全绿的情况下进入产物**。
- **影响**：`AGENTS.md:215` 把"Manifest、权限、依赖、备份"列为 `privacyCheck` 的验证范围，实际只有权限与无障碍被自动校验；导出组件与备份边界目前完全依赖人工评审。本次 `:app:lintDebug` 通过只说明当前清单没有触发**错误级**规则，不构成对导出组件/备份边界的约束。
- **置信度**：confirmed-by-reading。
- **建议**：在 `privacyCheck` 中断言 `application@allowBackup == false`、`@debuggable` 仅 debug 变体为 true、所有 `activity/receiver/provider` 的 `exported` 与批准清单（`MainActivity`、`ClipboardImportActivity`、`ZeroInputService`、`PageReferenceService`）逐项相等。

### L19 无障碍配置门禁可被新增资源限定符目录整文件绕过

- **位置**：`build.gradle.kts:216-219`、`:39-50`
- **证据**：`for (variant in listOf("xml", "xml-v31")) { if (!validPageReferenceConfig(...)) ... }`；`pageAccessViolation` 只匹配 Kotlin/Java 能力名，且 `:40` 对非 `/src/main/` 直接返回 `null`。
- **影响**：新增 `res/xml-v33/page_reference_service.xml` 并加入 `canRequestFilterKeyEvents` 即可在 API 33+ 生效，两层门禁同时不报错——把"一次性读取可见文字"扩权为"接收全部按键"；`src/debug`、`src/androidTest` 的无障碍 API 使用完全不参与扫描。
- **置信度**：confirmed-by-reading（当前仓库不存在该文件，属**未来改动的门禁缺口**）。
- **建议**：改为遍历 `res/xml*` 全部目录并拒绝未知限定符；黑名单补 `onKeyEvent`、`canRequestFilterKeyEvents`；扫描范围扩到全部 source set。

### L20 供应链固定的三处缺口

- **位置与证据**：
  1. `gradle/wrapper/gradle-wrapper.properties:3-5` 无 `distributionSha256Sum`（只有 `validateDistributionUrl=true`），Wrapper 分发包未做完整性固定，与 AGENTS §9 对第三方下载的要求不一致。
  2. `.github/workflows/publish-resources.yml:8-24`：工作流级 `permissions: contents: write`；`pip install numpy==… torch==… onnx==…` 未使用 `--require-hashes`；`actions/checkout` 未设 `persist-credentials: false`；`reviewed_ref` 接受任意 ref。任一 PyPI 依赖被投毒即可借仓库写权限发布资源。
  3. `tools/bootstrap-rime.ps1:36-41`：`if (Test-Path (Join-Path $destination "CMakeLists.txt")) { return }` 位于 `Get-FileHash` 之前——目录已存在时**跳过归档哈希校验**，且解压结果与归档哈希从未关联。
- **影响**：均为纵深防御缺口（需要上游/CI 侧前提），不改变当前运行时行为。
- **置信度**：confirmed-by-reading。
- **建议**：为 Wrapper 补官方 SHA-256；workflow 用 `pip --require-hashes -r requirements.txt`、设 `permissions` 到 job 级最小集、`persist-credentials: false`、限制 `reviewed_ref` 为 `main`；`bootstrap-rime.ps1` 把哈希校验移到目录存在判断之前，并核对解压内容与归档一致。

---

## 4. 性能与结构观察（不构成缺陷）

- **P1 个人分页与引擎分页并置后 `requested` 不回退**：`PersonalCandidatePaging.kt:35-56,89-93,116-121`。个人页 offset>0 时若回落到引擎翻页，`requested` 仍指向个人页，`hasNextPage` 取个人页的 `hasMore`；`CandidateScrollView` 以 `canLoad` 拒绝派发 NEXT，可能造成"下一页手势失效"。状态机可静态确认，出现条件（个人词条 ≥17 条且引擎 ≥3 页）needs-runtime。
- **P2 每键固定分配**：`rebuildRoutes`（`InputSessionController.kt:868-881`）与 `CandidateWindow.snapshot`（`:38-44`）每键各建一次 map/list，`associationsAllowed` 每键 1–2 次 `EditorInputOptions.from`。候选 ≤10，量级有界。
- **P3 列表类视图全量重建**：`AiContextView.kt:27-66`、`AiConversationListView.kt:30-58`、`SecureClipboardPanelView.kt:49-68` 每次刷新 `removeAllViews()`；`ExpandedCandidatesView.kt:182-192` 每键全量 DiffUtil + 每项 `measureText` + 无条件 `requestLayout`。
- **P4 滑行输入**：`GlideTouchTracker.kt:131-145` 每帧新建 `Path`，`KeyboardPanel.kt:103-144` 每次点按触发两次整块 `invalidate()`（即使滑行关闭）。滑行中 60–120 次/秒的重绘是全键盘帧预算竞争者。
- **P5 停靠/单手布局不预留底部系统栏 inset**：`KeyboardLayoutHost.kt:165-189` 仅浮动态/展开态扣除 `safeInsets`；若 IME 窗口自身未 inset，底排键可能与手势导航条重叠（needs-runtime，需真机 手势/三键 × 停靠/单手 比对）。
- **P6 每键计算学习串**：`RimeInputEngine.kt:86-87,105` 在**每次** `handle` 都算 `committedReading`/`learningInput`，而它们只在提交语义下被使用；`DictionaryEditReading.kt:24-28` 的双层循环内 `raw.substring(start)` 每个 `(start, code)` 新建 String（单音节 ≤8 256 次迭代，长阅读串可达数万次小对象分配/键）。建议改用 `raw.startsWith(code, start)`，或把计算推迟到 `selectCandidate`/Enter/标点提交时。

另有两个工程质量小项：`ZeroInputService.kt:133,1681-1688` 的 `engineReloadPending` 全仓只有赋值 `false`、从未置 `true`（死字段）；`ZeroInputService.kt:1736-1738` 的 KDoc「All callers run on the IME main thread」误挂在 `glideIdentity()` 上，而它描述的是 `registerInteraction()`。

两处口径不一致（均 fail-closed，不构成缺陷）：`LanguagePackParser.kt:27` 允许清单声明 `size = 0`，而 `LanguagePackDictionaryLoader`（`LanguagePackEngine.kt:169`）要求 `1..16 MiB`，因此含空附件的包会在"可用性检查"阶段整包失败，错误信息指向语言包而非空文件；`PublicDictionaryStore.enabledFiles()`（`:30-36`）在已启用词典的 blob 损坏时抛错，会让整轮 Rime 资产准备失败并退回内置降级引擎——恢复方式是在词典管理页停用该词典（`list()` 不校验 blob），属可恢复的 fail-closed。

---

## 5. 经复核确认正确的关键防线（防误报对照）

- **隐私分类**（`EditorPrivacyPolicy.kt:39-61`）：密码/可见密码/数字密码 → `isSensitive`；未知 `inputType` → `UNKNOWN_EDITOR` 保守拒绝；`IME_FLAG_NO_PERSONALIZED_LEARNING`、`TYPE_TEXT_FLAG_NO_SUGGESTIONS` 均使 `personalizationAllowed=false`；`NO_SUGGESTIONS` 分支保留引擎候选但强制 `predictionsAllowed=false`，与 §3.1/§3.4 一致。
- **个人数据读取收口**：`publish`→`personalPaging`、联想频率闭包、`recordUse`、`learn`、`associationsAllowed` 全部先看 `privacy.personalizationAllowed`；`updatePrivacy` 的顺序是 `reset` → `clearComposingText` → `replaceEngine` → 发布空快照，收紧后旧候选不会留在屏上。
- **会话/代次隔离**：`start()` 先 `closeEngine()` 再 `clearComposingText()`；`matches(prepared)` 同时校验 language/packKey/packageName/privacy 快照；`applyModelWinner` 按 `candidateRevision` 拒绝过期结果；两阶段粘贴的 `SecurePasteConsent` 具备 30s 双阶段、会话令牌、公开标识与删除代次校验。
- **加密原语**：十个别名互不共用；`setRandomizedEncryptionRequired(true)` 保证随机 IV；AAD 绑定文件名、格式版本与 IV 长度先校验后解密；`AuthenticationLifetime.consume` 先置 `consumed` 再判定（失败也烧毁）；五条 `clear()` 都在**加锁前**提升代次，配合"执行前 + 提交前"双校验，"排队写入复活已删数据"不可达；清除失败为 fail-closed 且可由再次 `clear()` 解除，`SettingsTaskRunner` 把失败折到 `operation_failed` 并提供重试入口。
- **网络边界**：仅 `OpenAiCompatibleProvider` 与 `DictionaryDownloadTransport` 两处传输；`instanceFollowRedirects=false`，3xx 直接映射 `REDIRECT` 且不读 body；HTTPS + host 白名单 + 端口限制 + 禁 userInfo/fragment；请求体 2 MiB、SSE 行 16 KiB、单事件 16 KiB、流 2 MiB、输出 32768 字符五层上限都在**流式读取过程中**生效；缺 `[DONE]`/`message_stop`/`response.completed` 即判失败。
- **页面引用（无障碍）**：仅 `flagRetrieveInteractiveWindows` + `canRetrieveWindowContent`，事件只读元信息；三重排除（可见性/可编辑/密码/包名/敏感数据）、遮挡矩形、焦点复验、锁屏校验、节点与窗口全 `recycle`、5s 采集与 30s 审阅超时、`referenceToken` 代次复核。
- **资源导入**：`PublicResourceStore.unpack` 以"归档条目名 == 目录声明名"严格匹配，写入目标名恒为 `spec.sha256`（无路径穿越），逐条 `size` + 流式哈希（防 zip bomb），staging + `AtomicFile` 注册表 + 失败保留旧状态，`leases` 引用计数阻止在用 blob 被覆盖。
- **UI 职责边界**：`ime-ui` 内无磁盘/Keystore/解密/`PersonalizationStore` 引用、无 `Log.` 调用；`ZeroInputView.release()` 逐个清理回调；`values` 与 `values-en` 的键集与占位符由 UI 域逐键比对一致（差集仅 `translatable="false"` 的符号）。
- **构建脚本的既有固定**：librime/octagram/leveldb/marisa/opencc/yaml-cpp 钉 commit + 归档 SHA-256，boost 1.89.0 钉哈希，xz 1.10 与 reduced ONNX runtime 每次 `preBuild` 复核哈希；`testPrivacyBoundary` 自检含"路径前缀伪装""合法写 + 越权写混合"反例并随 `privacyCheck` 强制执行。
- **引擎生命周期**（本轮逐行复核）：`initialize/failInitialization/close/markFailed/releaseEngine` 的 generation 与 in-flight 标记闭合，`finalizeWhenIdle` 只在会话计数归零且无初始化在途时触发；第五轮 D1-1（"延迟 finalize 永不触发 / `nativeInitialized` 反向竞态"）已确认修复——`nativeInitialized` 现在与 `nativeInitializationGeneration` 在同一把锁内先置位，再由 generation 检查决定是否 `finalizeNativeLocked()`；`RimeInputEngine.close()` 的 `onClosed()` 只在 `sessionId != 0` 时执行，计数不会重复递减。
- **语言包导入边界**：128 MB 归档 / 256 MB 解压 / 2 048 条目 / 256 KB 清单闸门；逐条 `size + sha256`；中央目录类型白名单（拒绝 symlink/设备/FIFO）；重复名、绝对路径、盘符、`..`、空段全部拒绝；staging → 原子替换 + 失败回滚；`readInstalled` 复核目录名与 manifest 一致并重算每个 payload 哈希；`zh/en` 标签白名单 + JSON 深度 16 预扫描。
- **JNI 与模型加载**：JNI 侧参数校验、RAII（`ContextLease`/`CommitLease`）、schema 名与路径前缀、caret 位置与长度、候选页上界；模型加载校验 bytes+sha256、vocab 21 128 项、CTC 维度等式、笔画特征索引上界；`PAGE_SIZES={5,8,10}` 与 JNI `size<=10` 一致。
- **第五轮 P4 已修**：`KeyboardLayoutHost.kt:174-180` 改为 `windowHeightCache.resolve(...)`，仅在 attach/size 变化时失效，不再每键 binder 取 `currentWindowMetrics`。

---

## 6. 验证记录

本轮实际执行的命令（`JAVA_HOME=D:\env\jdk17`，Gradle 8.14 Wrapper）：

```powershell
./gradlew.bat :app:testDebugUnitTest :ime-core:testDebugUnitTest :user-data:testDebugUnitTest `
  :engine-rime:testDebugUnitTest privacyCheck :app:lintDebug --no-parallel
# BUILD SUCCESSFUL in 30s（380 actionable tasks）

./gradlew.bat :engine-api:test :engine-english:test :engine-dictionary:test :ai-api:test `
  :model-scoring:testDebugUnitTest --no-parallel
# BUILD SUCCESSFUL
```

测试结果（读取各模块 `build/test-results` 汇总）：`app` 262、`ime-core` 139、`user-data` 60、`engine-rime` 65、`engine-english` 12、`language-pack` 44、`model-scoring` 20、`security` 10、`ime-ui` 46、`engine-dictionary` 30、`engine-api` 5、`ai-api` 13 —— **合计 706，0 failure，2 skipped**。

**未运行**（因此不以"通过"表述）：`androidTest`/模拟器/真机验证、Release 构建、`-PrequireRime=true` 的 Rime 源码级验证、`tools/package-test-apk.ps1` 产物校验、真实 AI 端点的协议联调。上述未运行项直接影响：M3 的服务端裁决、M4 的 JNI 失败行为、L8 的爆栈阈值、P1/P5 的可见表现。

---

## 7. 判定为"设计如此"或"不成立"的项

- **`SharedPreferences.OnSharedPreferenceChangeListener` 在后台写线程上回调**：不成立。AOSP `SharedPreferencesImpl.notifyListeners` 在调用线程不是主线程时会 `post` 到主线程，因此 `SecurePasteCoordinator` 的监听器（进而 `ZeroInputService.bindPasteConsent`）始终在主线程执行，不存在跨线程改 View 的崩溃路径。
- **`handleEnter` 用 `rawInput` 而非 `fallbackComposition`**：`docs/architecture.md:607-609` 明确规定"有组合时 Enter 提交原始输入"，且 `PartialCompositionFallbackTest` 已固化，非缺陷。
- **`PersonalCandidatePaging` 的 PREVIOUS 边界**（"合并首页被逐出后才接管"）：与 `hasPrevious` 语义一致，未发现越界或错页。
- **清除失败后仓库不可用**：属 fail-closed 设计，且 `clear()` 可重入重试、设置页有 `operation_failed` 提示，不是第五轮报告过的"永久死锁"。
- **`AiEndpoint.parse` 不限制主机**：端点由用户自填（ADR 0024），只做 https/origin 约束，属设计。
- **`PageScreenshotBroker` 无生产实现**：`docs/architecture.md:755-761` 已声明适配器不可用，属休眠边界（需保证 ADR 0019 未批准前不被接线）。
- **纯风格项**（KDoc 位置、死字段、`::` 调用形式等）：除 §4 末尾两条外不单列。
- **JNI `nativeInitialize` 的幂等短路不校验目录对**：`engine-rime/src/main/cpp/zero_rime_jni.cpp:138-141` 在 `initialized` 为真时直接返回 `TRUE`，之后才写入新目录；若出现第二个 `RimeRuntime`，它会静默复用第一个实例的目录，且任一实例 `close()` 会把全局状态一并 finalize。当前 `ZeroInputApplication` 进程内只创建一个 `AppGraph`，因此不可达；记录为"多实例化前提下的隐患"，不作为本轮缺陷。

---

## 8. 建议处理顺序

1. **M1**（干净检出构建失败）——影响所有新贡献者与 CI-from-scratch，一行 `onlyIf` 即可。
2. **M2**（空格被吞/候选清空）——破坏输入正确性，且在"词库不可读"时是永久失效；与 `AGENTS.md` §1 决策优先级第 2 条（输入正确性）直接冲突。
3. **M3**（Anthropic 深度思考必失败）——单点配置即可复现的功能损坏，补一项编码不变量测试。
4. **M7**（380 MiB 语法模型每次初始化全量重哈希）——直接决定冷启动的中文可用时延，也是"预热请求被拒"的根因；建议与资源校验票据一起设计。
5. **M8**（语言包前缀索引桶数量放大）——导入边界内的 OOM 风险，改动集中在 `buildPrefixIndex` 与前缀长度上限。
6. **M5**（安全剪贴板两次落盘）与 **M4**（引擎失败无状态）——可恢复性与可观测性缺口，建议同批处理并补负向测试。
7. **M6**（每键重复渲染）——先加末值判等，再用 Perfetto 验证收益。
8. **L12/L18/L19/L20**（异常防护与门禁）——属纵深防御，可与文档修订同批；L1/L13（九键交接、语言包分节符）适合随下一次输入体验迭代修复。
9. 其余 L/P 项按模块归属排期。

---

*本报告仅为静态审查结论与证据汇编，不代表运行时实测。所有行号基于 HEAD `2bc301b`。*
