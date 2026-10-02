# ZeroInput 代码审查反馈

- 审查对象：`L:\zeroInput` 工作树（13 个 Gradle 模块，约 7.0 万行一方代码；不含 `third_party/`、`build/`、`.cxx/`）
- 审查方式：逐文件阅读一方源码，并与 `AGENTS.md` §3–§11 的隐私、线程、架构约束及 `docs/architecture.md` 逐条对照
- 状态：**只读审查，未修改任何源代码、测试、Lint 规则或隐私门禁**
- 本文件性质：反馈文档，供维护者决定修复范围与优先级

## 0. 范围与判定原则

本报告只收录**可证实的缺陷**，并已在成文前排除"设计如此"的项（见 §6）。每一条都给出文件、行号、原始代码与可观察后果。凡属推断而非确证的，均在"置信度"中标注。

工作区当前存在未提交的修复（`git status` 列出 11 个修改文件），对应 `docs/code-audit-2026-09-30.md` 的 C1、H1、H2、H4、H6、M5、M8。本次审查逐条复核了这些补丁：**其中 3 条修复不完整或引入了新缺陷**（D1-1、D2-1、D2-2），**另有 5 条尚未修复**（D2-3、D2-4、D3-1、D3-5、D3-6）。已正确修复的部分记录在 §5。

严重度定义：

| 级别 | 含义 |
| --- | --- |
| 严重 | 可导致进程崩溃、安全/隐私边界失效，或使核心功能不可用且无法自愈 |
| 高 | 明确的用户可见功能损坏、资源泄漏、门禁失效，或需要重启才能恢复 |
| 中 | 性能退化、死代码/未接线功能、可维护性风险，或文档与实现不一致 |
| 低 | 局部健壮性与一致性问题 |

---

## 1. 严重缺陷

### D1-1 `RimeRuntime` 的"延迟 finalize"标记在恢复路径上永不触发，且存在反向竞态

**位置**：`engine-rime/src/main/kotlin/dev/zeroinput/engine/rime/RimeRuntime.kt:175-188`、`:211-225`、`:228-241`

未提交补丁为解决"native 运行时在会话存活时被 finalize"，引入 `finalizeWhenIdle` 标记，只在 `releaseEngine()` 中消费：

```kotlin
private fun releaseEngine() {
    if (activeEngines.decrementAndGet() != 0) return
    synchronized(lock) {
        if (finalizeWhenIdle) { finalizeNativeLocked(); finalizeWhenIdle = false }
        ...
```

**缺陷 1（标记永不消费）**：`InputSessionController` 的错误恢复路径绕过了 `releaseEngine()`：

```kotlin
// ime-core/.../InputSessionController.kt:773-783
private fun recoverFromEngineFailure(failedEngine: InputEngine) {
    val snapshot = state.snapshot.takeIf(EngineSnapshot::isComposing)
    if (engine === failedEngine) closeEngine() else closeSafely(failedEngine)
```

`closeEngine()`（`:760-767`）与 `closeSafely()`（`:769-771`）直接调用引擎自身的 `close()`；原生引擎只销毁自己的会话（`RimeInputEngine.kt:212-226`），不会回调 `releaseEngine`，因此 `activeEngines` 不再回落到 0。

触发序列（普通打字即可到达）：

1. 一次 native 调用抛异常 → `RimeInputEngine.nativeCall` 调 `onNativeFailure` → `markFailed`（`:228-241`）。此时 `activeEngines.get() != 0`，走 `else` 分支设 `finalizeWhenIdle = true` 并置 `FAILED`。
2. `InputSessionController.handle` 的 `catch (_: Throwable)`（`:198-204`）捕获 → `recoverFromEngineFailure` → `closeSafely` → 引擎自行 `close()`，计数不减。
3. 后续重建会话、切换语言、`endInputSession` 走的都是 `controller.close()` → `closeEngine()` → `closeSafely`，同样不过 `releaseEngine`。

结果：`finalizeWhenIdle` 永久为 `true`，`NativeRimeBridge.nativeFinalize()` **永不执行**。运行时已 `FAILED`，native 全局状态却持续驻留；`initialize()` 又会因 `activeEngines.get() != 0` 被拒（`:59`），重试路径无法自愈。

**缺陷 2（反向竞态）**：`nativeInitialized = true`（`:84`）与 `close()` 的 finalize 之间存在窗口：

```kotlin
check(NativeRimeBridge.nativeInitialize(...)) { ... }
nativeInitialized = true                                   // :84，锁外
RimeInputEngine().use { RimeSessionVerifier.verify(it) }
synchronized(lock) {
    if (generation != initializationGeneration || state != RimeRuntimeState.INITIALIZING) {
        finalizeNativeLocked()                             // :91
        return false
    }
```

- 若 `close()`（`:211-225`）落在此窗口内：`activeEngines == 0` → 调 `finalizeNativeLocked()`，但此刻 `nativeInitialized` 仍为 `false`，函数**直接 return 不做清理**；随后 `initialize()` 把标志置为 `true` 并继续用已"关闭"的运行时创建会话。
- 若 `close()` 在 `nativeInitialize()` 返回**之前**执行、`initialize()` 随后完成：finalize 被跳过，`nativeInitialized` 与 librime 真实状态可长期不一致，后续 `markFailed`/`close` 会对未初始化的运行时调用 `nativeFinalize()`。

`nativeInitialized`/`finalizeWhenIdle` 是跨线程可变字段（`@Volatile` 只保证可见性，不保证复合操作原子），而 `activeEngines` 的自增/自减分别发生在 `synchronized` 块内外。

**影响**：native 生命周期与 Kotlin 侧状态可以长期不一致，症状为进程崩溃（输入法静默消失或反复重启）或降级状态无法恢复。

**置信度**：confirmed-by-reading（路径与锁序逐行核对）；运行时可观察症状需设备确认。

**建议方向**：让所有会话释放都经过同一个计数出口（例如由引擎 `close()` 统一回调释放钩子），并在 `releaseEngine` 之外提供"计数已为 0 时补做 finalize"的收敛点；`nativeInitialized` 的置位与检查纳入同一把锁，或改为由 librime 状态推导而非独立标志。

---

## 2. 高优先级缺陷

### D2-1 `clear()` 失败会让四个加密仓库在进程内永久不可用（上一轮已报告，仍未修复）

**位置**：

- `user-data/src/main/kotlin/dev/zeroinput/userdata/UserLexiconRepository.kt:175-185`
- `user-data/src/main/kotlin/dev/zeroinput/userdata/SecureClipboardVault.kt:124-135`
- `user-data/src/main/kotlin/dev/zeroinput/userdata/EmojiHistoryRepository.kt:48-55`
- `user-data/src/main/kotlin/dev/zeroinput/userdata/PersonalExpressionRepository.kt`（`clear()`）

```kotlin
override fun clear() {
    generation.incrementAndGet()
    synchronized(lock) {
        publishTerms(emptyList())
        deletionPending = true
        store.delete(deleteKey = true)          // 抛异常则下一行永不执行
        if (exportCipher.hasKey()) exportCipher.deleteKey()
        deletionPending = false
    }
}
```

`deletionPending` 的重置不在 `finally` 中。`SecureClipboardVault` 的 `finally` 只包住 `delete` 本身，`deletionPending = false` / `indexNeedsRepair = false`（`:132-133`）仍在 `finally` 之外；`EmojiHistoryRepository` 完全没有 `finally`。

`EncryptedFileStore.delete()`（`security/.../EncryptedFileStore.kt:60-65`）**确认会抛异常**：

```kotlin
override fun delete(deleteKey: Boolean) = synchronized(lock) {
    try { atomicFile.delete() } finally { if (deleteKey) cipher.deleteKey() }
    if (listOf(file, File(file.path + ".bak"), File(file.path + ".new")).any(File::exists)) {
        throw IOException("Encrypted data deletion is incomplete")
    }
}
```

`cipher.deleteKey()` 中的 `KeyStore.deleteEntry` 可抛 `KeyStoreException`/`ProviderException`；写入后置校验也会抛 `IOException`。

**影响**：`deletionPending` 永久为 `true`，此后每一次读写都抛错——`loadTerms()`（`:264`）、`persist()`（`:275`）、`SecureClipboardVault.checkStorageAvailable()`（`:144-146`）、`EmojiHistoryRepository.load()`（`:58`）。用户看到的是"清除数据后学习、安全剪贴板、emoji 历史全部持续失败"，即使数据其实已删除，**只能杀进程恢复**。

`docs/architecture.md:288-292` 把这描述为"不完整的删除会阻止访问，直到显式重试成功"，但代码中**没有任何重试入口**——设置页不会再次触发 `clear()`，因此"直到重试"在实践中等于"直到重启"。`docs/releases/v0.3.0.md` 也把"清除失败处理"列为已完成项。

**置信度**：confirmed-by-reading

**建议方向**：`deletionPending` 的重置移入 `finally`，并把"删除是否成功"与"仓库是否可用"拆成两个可判定状态；为清除失败提供显式的用户可见重试入口。

### D2-2 `AppGraph` 初始化提交任务未捕获执行器拒绝，队列饱和时启动即崩溃

**位置**：`app/src/main/kotlin/dev/zeroinput/ime/AppGraph.kt:174-187`

```kotlin
init {
    readAiConfiguration {}
    engineExecutor.execute {          // 无 runCatching
        associationPredictor = runCatching { WordAssociationIndex.loadBundled() }...
        runCatching { rime.warmUp() }
        runCatching { refreshLanguagePacks() }
    }
}
```

`engineExecutor` 为 `BoundedExecutors.singleThread(queueCapacity = 2)`（`:41-44`），底层是 `ThreadPoolExecutor` + `AbortPolicy()`（`concurrency/BoundedExecutors.kt:38`），队列满时**抛 `RejectedExecutionException`**。

**影响**：异常从 `AppGraph` 构造器抛出 → `ZeroInputApplication.onCreate` 抛出 → 进程崩溃（输入法启动即消失）。触发条件是 3 个任务在 worker 启动前涌入队列；`engineExecutor` 是共享的，`prepareEngine`（`:219`）与语言包注册表也向同一队列提交。这与 `AGENTS.md` §6"异步结果必须针对……执行器拒绝……具有确定行为"直接冲突。

值得注意：同一文件的其他提交点都做了保护（`readAiConfiguration` `:101`、`updateAiConfiguration` `:138`、`enqueueAiControl` `:135-139`），唯独最关键的初始化路径没有。

**置信度**：confirmed-by-reading（异常可达性依赖并发时序）

**建议方向**：照同文件既有模式包 `runCatching`/`try-catch RejectedExecutionException`，把初始化任务改为带重试的惰性提交，或提高容量并让初始化成为不可拒绝的优先任务。

### D2-3 `privacyCheck` 网络门禁存在多个可直接绕过的 API 缺口

**位置**：`build.gradle.kts:27-35`

```kotlin
fun networkViolation(path: String, text: String): String? {
    val network = Regex("""(?:java\.net\.(?:URL\b|Socket\b|Datagram|Http)|javax\.net\.|HttpURLConnection|HttpsURLConnection|openConnection\s*\(|android\.webkit|okhttp|retrofit|ktor\.client|Cronet)""")
```

**实测结果**（正则行为已逐条验证）：

| 可发起网络/拉取远程内容的 API | 是否被拦截 |
| --- | --- |
| `java.net.http.HttpClient`（JDK 11 HTTP 客户端） | 否 |
| `java.nio.channels.SocketChannel` | 否 |
| `WebView`（正则只匹配 `android.webkit` 字面量） | 否 |
| `DownloadManager` | 否 |
| `Class.forName("java.net.URL")` 等反射 | 否 |
| `"java." + "net.Socket"` 字符串拼接 | 否 |

**影响**：`privacyCheck` 名义上是网络边界门禁，实际只是一组字面量正则，能拦住顺手写出的代码，拦不住使用 NIO/JDK11 HTTP 客户端或任何有意的实现。`AGENTS.md` §3.1 要求"不得增加其他联网路径"，§14 禁止"使用反射、字符串拼接、native 绕行或重命名来规避系统剪贴板与权限扫描"——规则只约束了人，工具没有对应的检测能力。

**现状澄清**：当前生产代码确实只用 `HttpURLConnection`，因此这是**门禁强度问题，不是现存漏洞**。但 `docs/code-audit-2026-09-30.md` 将其记为"L6 低风险"低估了它。

**次要问题**（同一函数）：`text.contains("android.permission.INTERNET")` 在任意文件中命中即报错（含注释与文档字符串）；`provider` 白名单是**整文件**放行——`OpenAiCompatibleProvider.kt` 未来加入任何其他用途的网络调用都不会被拦。

**置信度**：confirmed-by-reading（正则行为已实测）

**建议方向**：从"字面量黑名单"转向"字节码/依赖层白名单"（例如只允许 `HttpURLConnection` 且限定调用点，或对 `java.net`/`java.nio.channels`/`android.webkit` 整个包的引用做拦截），并在 `testPrivacyBoundary` 中加入上述缺口的负向用例。

### D2-4 `privacyCheck` 剪贴板写入白名单粒度不足

**位置**：`build.gradle.kts:17-23`

```kotlin
val writes = Regex("\\bsetPrimaryClip\\s*\\(").findAll(text).count()
val allowedWrite = if (path == adapter) {
    Regex("setPrimaryClip\\(ClipData\\.newPlainText\\(\"\", \"\"\\)\\)")
```

计数正则允许 `setPrimaryClip (` 形式的空白差异，允许正则不允许，两者口径不一致（偏严，可接受）。**真正的问题是白名单粒度**：`AndroidSystemClipboard.kt` 与测试 fixture 是**整文件**白名单，只要把任意剪贴板操作放进这两个文件即可通过检查；门禁不校验出现位置、上下文或调用者。

**影响**：`AGENTS.md` §3.2 规定"仅允许上述适配器及指定的构造数据平台测试使用"，但门禁无法阻止适配器文件被当作通用剪贴板工具使用。这也与 §14"不得……放宽……导入边界，只为让变更通过"的意图不完全匹配——文件级豁免比规则本身更宽。

**置信度**：confirmed-by-reading（allowlist 粒度确认为设计缺陷；具体绕过构造需实证）

**建议方向**：把允许的写入形式收紧为唯一语句并校验出现次数；对适配器文件额外校验"不得出现读取 API"，或把豁免改为按行匹配而非按文件。

### D2-5 `ClipboardGuardRuntime` 的重试链可无界自我复制并持续占用主线程

**位置**：`app/src/main/kotlin/dev/zeroinput/ime/clipboardguard/ClipboardGuardRuntime.kt:137-156`

```kotlin
private fun enqueueRefresh() {
    if (closed.get()) { refreshQueued.set(false); return }
    if (enqueue {
        try { refresh() } finally {
            val changedWhileRefreshing = !closed.get() && lease != revision.get()
            refreshQueued.set(false)
            if (changedWhileRefreshing && refreshQueued.compareAndSet(false, true)) enqueueRefresh()
        }
    }) return
    main.postDelayed(::enqueueRefresh, REFRESH_RETRY_DELAY_MS)   // 50 ms，无退避、无上限
}
```

两个问题：

1. **重试链不受 `refreshQueued` 保护**。`enqueue` 被拒时会执行 `revision.incrementAndGet()`（`:259-262`），因此下一次 `refresh()` 的 `lease != revision` 几乎必然成立，`finally` 会再启动一条 `enqueueRefresh()`；同时外层还有一条 `postDelayed` 链。两条链在 `compareAndSet(false, true)` 上的耦合很弱，可能并存。
2. **无尝试次数上限、无退避**。只要 worker 队列持续满（容量 8，`:25`），主线程每 50 ms 被唤醒一次，且每次拒绝都会 `revision.incrementAndGet()`，使 `mayAccess()`（`:214`）持续为 `false`、防护长期失效。

上一轮补丁修好了"在调用线程内联执行 `refresh()`"这一更严重的问题（方向正确），但引入了这条无界重试链；本质上把"主线程阻塞 I/O"换成了"主线程忙轮询"，仍与 `AGENTS.md` §6 对热路径的要求冲突。

**置信度**：confirmed-by-reading（重试链无上限已确认；双链并存依赖时序，属高置信推断）

**建议方向**：重试改为单条链并由 `refreshQueued` 独占；加入退避与最大尝试次数；队列持续饱和时退化为"下次生命周期回调再刷新"。

### D2-6 `ClipboardGuardOverlay` 在 `addView` 之后失败时泄漏已注册的 `BroadcastReceiver`

**位置**：`app/src/main/kotlin/dev/zeroinput/ime/clipboardguard/ClipboardGuardOverlay.kt:88-97`、`:115-124`

```kotlin
return try {
    window = view
    manager.addView(view, parameters)
    ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    watching = true
    appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, permissionChanged)
    main.postDelayed(expire, options.overlaySeconds * 1_000L)
    true
} catch (_: RuntimeException) { hide(); false }
```

```kotlin
fun hide() {
    main.removeCallbacks(expire)
    window?.let { runCatching { manager.removeViewImmediate(it) } }
    window = null
    if (watching) {                       // watching 只在全部成功后置 true
        runCatching { context.unregisterReceiver(screenOff) }
        runCatching { appOps.stopWatchingMode(permissionChanged) }
        watching = false
    }
}
```

`registerReceiver` 成功、紧随其后的 `startWatchingMode` 抛 `RuntimeException`/`SecurityException`（部分 OEM 会抛）或 `postDelayed` 抛错时，`watching` 仍为 `false`，`hide()` 不会注销 `screenOff`。每次重建悬浮窗可再泄漏一个 receiver，直到 `close()`。同一模式也影响 `permissionChanged` 的 `startWatchingMode`。

**置信度**：confirmed-by-reading

**建议方向**：把 `registerReceiver`/`startWatchingMode` 与"已注册"标志的置位改为不可分离的一对（先置标志再注册、失败即在同一 `catch` 中注销），或让 `hide()` 无条件尝试注销并容忍失败。

### D2-7 安全剪贴板清理路径缺少上一轮明确要求的回归测试

**位置**：`app/src/main/kotlin/dev/zeroinput/ime/clipboardguard/ClipboardClearActivity.kt`（已修）；`app/src/androidTest/kotlin/dev/zeroinput/ime/clipboardguard/ClipboardGuardRuntimeTest.kt`、`ClipboardGuardPanelTest.kt`

上一轮审计对 `focused` 被替换为新 `AtomicBoolean` 的缺陷给出修复方向："use one mutable flag … **and add a regression test that drives `ClipboardClearActivity`'s confirmation button and asserts `CLEARED`**"。`git diff` 显示代码已改为 `focused.set(true)`，但**未新增任何测试**。现有两个测试注入自己的谓词，正是上一轮指出"do not cover this path"的那两个。

**影响**：这条曾经导致"通知/悬浮窗/设置页三个入口全部无法清理剪贴板"的主路径，仍只有人工验证覆盖。`AGENTS.md` §11.2 要求高风险场景必须有自动化覆盖。

**置信度**：confirmed-by-reading

---

## 3. 中优先级缺陷

### D3-1 `PrivacyConfiguration.excludedPackages` 是不可达的死代码，且文档声称该功能存在

**位置**：`ime-core/src/main/kotlin/dev/zeroinput/ime/core/privacy/EditorPrivacyPolicy.kt:9`、`:34`、`:50-52`；生产侧唯一构造点 `app/src/main/kotlin/dev/zeroinput/ime/settings/SettingsRepository.kt:145-148`

```kotlin
fun privacyConfiguration() = PrivacyConfiguration(
    learningEnabled = learningEnabled,
    incognitoMode = incognitoMode,
)   // 从不传 excludedPackages
```

全仓库检索确认：`excludedPackages` 只出现在上述声明与 `EditorPrivacyPolicy` 的读取处，**没有任何生产代码或测试为其赋值**；`PACKAGE_EXCLUDED` 只在枚举声明与那个不可达分支中出现。因此 `:50-52` 是死分支，`PACKAGE_EXCLUDED` 永不产出。

**附加问题**：`docs/model-integration.md:22` 明确写着 "Password/PIN/unknown/email/URI fields, no-personalization flags, incognito, **excluded editors** and learning-disabled sessions cannot supply model context"——文档承诺了实现中不存在的能力。

**置信度**：confirmed-by-reading

**建议方向**：二选一——补齐"按应用排除"的设置项与持久化（含 UI 与测试），或删除 `excludedPackages`、`PACKAGE_EXCLUDED` 及文档中的 "excluded editors" 措辞。`AGENTS.md` §10.1 明确反对保留无需求的抽象。

### D3-2 `README.md` 构建步骤缺少必需的资产准备命令，照文档构建必然失败

**位置**：`README.md:404-416`；`model-scoring/build.gradle.kts`（`prepareModelAssets`）

README 只给出三条准备命令：

```powershell
python -B tools/evaluate-small-model.py --model mini --download
python -B tools/export-model-benchmark.py --model mini --int8
python -B tools/prepare-handwriting-model.py --include-quality-fixtures
```

但 `prepareModelAssets` 还硬性要求另外两个文件：

```kotlin
val strokeModels = listOf(
    Triple(rootProject.file("build/handwriting-stroke-model/stroke-simplified.zsh"), 7_016_330L, "fdd47959..."),
    Triple(rootProject.file("build/handwriting-stroke-model/stroke-traditional.zsh"), 39_052_454L, "7eaa6200..."),
)
```

它们由 `tools/prepare-handwriting-stroke-model.py` 生成，而该脚本**在 `README.md`、`AGENTS.md` §12、`tools/package-test-apk.ps1` 中均未被提及**。缺失时 `check(file.isFile && file.length() == size)` 使 `:model-scoring:prepareModelAssets` 失败，而它挂在 `preBuild` 上（`tasks.named("preBuild").configure { dependsOn(prepareModelAssets, verifyRuntimeArtifact) }`），因此**任何** `assembleDebug` 都会失败。

错误信息本身也误导：`"Prepare pinned model assets: see docs/model-integration.md, tools/prepare-handwriting-model.py and tools/prepare-handwriting-stroke-model.py"` 把生成 stroke 模型的脚本与生成手写视觉模型的脚本混在一起，且 `docs/model-integration.md` 的 Reproduce 段**只列出两条** python 命令（缺少 `prepare-handwriting-model.py` 与 `prepare-handwriting-stroke-model.py`）。

**资产不在仓库中的证据**：`git ls-files build` 返回 0；`git check-ignore -v` 显示 `.gitignore:7:**/build/` 命中了 `build/model-evaluation/...` 与 `build/handwriting-model/...`。另 `tools/prepare-emoji.py` 同样未在文档中列出（`ime-ui` 的 `verifyEmojiAssets` 会校验 3973 个文件的 SHA-256）。

**置信度**：confirmed-by-reading（`git ls-files`、`git check-ignore` 已实测）

**建议方向**：补齐四条准备命令并在 README 与 `docs/model-integration.md` 中保持一致；修正 `check` 的提示文本；考虑让缺失资产时的失败信息直接给出可复制的命令。

### D3-3 语言包引擎每次按键执行全表线性扫描（主线程 O(n)）

**位置**：`language-pack/src/main/kotlin/dev/zeroinput/languagepack/LanguagePackEngine.kt:118-130`

```kotlin
private fun createSnapshot(): EngineSnapshot {
    if (input.isEmpty()) return EngineSnapshot.Empty
    if (!candidatesAllowed) return EngineSnapshot(rawInput = input, composition = input)
    val candidates = entries.asSequence()
        .filter { (shortcut, _) -> shortcut.startsWith(input, ignoreCase = true) }   // 全表扫描
        .flatMap { (shortcut, values) -> values.asSequence().map { shortcut to it } }
        .map { (_, value) -> value }
        .distinct()
        .take(MAX_CANDIDATES)
        .mapIndexed { index, value -> Candidate("pack:$index:$value", value) }
        .toList()
```

`entries` 是 `groupBy { it.shortcut }` 的结果（`:44-46`），上界由 `MAX_ENTRIES = 50_000`（`:147`）界定。`createSnapshot()` 在 `handleCharacter`（`:92`）与 `handleBackspace`（`:99`）中同步调用，而 `InputEngine.handle` 运行在 IME 主线程（`InputSessionController.handle` → `engine.handle`）。

**影响**：第三方语言包可让**每次击键**遍历最多 5 万个键并构造 `Pair`/`Sequence`/`List`，违反 `AGENTS.md` §6"输入主线程只读取已准备好的内存快照，并尽快返回"。对照实现：`engine-english` 用 `buildPrefixIndex`（`EnglishInputEngine.kt:214-222`）预建前缀索引，`engine-dictionary` 用 `ReferenceDictionary` 做前缀表——只有语言包引擎采用线性扫描，而它的数据完全由外部导入文件控制。

**置信度**：confirmed-by-reading

**建议方向**：在工厂构造时（引擎后台 worker 上）预建前缀索引，与 `engine-english`/`engine-dictionary` 保持一致。

### D3-4 英文引擎 `"Did you mean?"` 纠错功能完全不可达

**位置**：`engine-english/src/main/kotlin/dev/zeroinput/engine/english/EnglishInputEngine.kt:20`、`:145-151`；`EnglishEngineFactory.kt:15`

```kotlin
private val correctionsEnabled: Boolean = false,     // 默认 false
```

```kotlin
override fun create(): InputEngine = EnglishInputEngine(learnedSuggestions)   // 从不传 correctionsEnabled
```

全仓库唯一的 `correctionsEnabled = true` 出现在测试中（`EnglishInputEngineTest.kt:146`）。生产构建永不进入 `:145` 的整表 `isSingleEditAway` 循环，`CORRECTION_SCORE_BASE` 与 `"Did you mean?"` 注释串均为死代码。

**附加问题**：`Candidate.comment` 在生产中只有一处消费者——`ime-ui/.../CandidateItemView.kt:60` 把它设为 tooltip，在触屏键盘上几乎不可见。也就是说即使接线，用户也难以看到纠错提示。

**置信度**：confirmed-by-reading

**建议方向**：明确二选一——接线为可选设置（含 UI 与测试），或删除该参数、评分常量与相关分支。`AGENTS.md` §10.1 反对保留未使用的通用框架。

### D3-5 `EmojiHistoryRepository` 每次提交都做一次全表 `maxOf` 扫描

**位置**：`user-data/src/main/kotlin/dev/zeroinput/userdata/EmojiHistoryRepository.kt:21-36`

```kotlin
current[emoji] = EmojiUsage(emoji, ((old?.count ?: 0) + 1).coerceAtMost(EmojiHistoryFormat.MAX_COUNT),
    maxOf(System.currentTimeMillis(), current.values.maxOfOrNull { it.lastUsed }?.plus(1) ?: 0L))
```

`current.values.maxOfOrNull` 是对全部历史条目（`MAX_ENTRIES` 量级）的线性扫描，每次 `recordIfRevision` 都执行；同时每次提交都会读盘（`:25`）并全量重写（`:30-34`）。该路径在 `localDataExecutor` 后台线程（`ZeroInputService.kt:938-956`），不阻塞按键，但把 O(1) 的时间戳更新放大为 O(n) 读改写。

`maxOf(max(now), oldMax + 1)` 的目的只是保证单调递增，保存一个实例级高水位即可。

**置信度**：confirmed-by-reading

### D3-6 `prepareOpenCcAssets` 在缺少 native Rime 时静默跳过全部哈希校验

**位置**：`engine-rime/build.gradle.kts`（`prepareOpenCcAssets` 任务）

```kotlin
val prepareOpenCcAssets = tasks.register<Sync>("prepareOpenCcAssets") {
    onlyIf { hasNativeRime }
    from(dictionaryRoot) { include(hashes.keys) }
    into(openCcAssets.map { it.dir("rime/opencc") })
    doFirst {
        hashes.forEach { (name, expected) -> ... check(actual == expected) { "Pinned OpenCC dictionary checksum mismatch" } }
    }
}
```

`onlyIf { hasNativeRime }` 使整个任务（含 `doFirst` 的校验）在未运行 `bootstrap-rime.ps1` 时被跳过。于是"第三方内容必须固定哈希校验"这一 `AGENTS.md` §9 的强制要求，在降级构建中**完全没有执行**，同时 OpenCC 资产也不会被打包。

对照：`prepareSyllableAssets` 的 `check(syllables.size in 100..1024)` 无条件执行，`prepareModelAssets` 的 `check` 也无条件执行——三者的失败关闭行为不一致。

**置信度**：confirmed-by-reading

**建议方向**：把哈希校验移出任务体（例如独立的 `verifyOpenCcAssets` 任务，不受 `onlyIf` 影响），或在 `onlyIf` 为假时显式记录"已跳过校验"而非静默通过。

### D3-7 `ReferenceDictionary` 静默截断词典数据

**位置**：`engine-dictionary/src/main/kotlin/dev/zeroinput/engine/dictionary/ReferenceDictionary.kt:19-21`

```kotlin
stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
    lines.filter { it.isNotBlank() && !it.startsWith("#") }.take(512).forEach { line ->
```

`take(512)` 在无任何提示的情况下丢弃第 513 行起的词条；`check(entries.isNotEmpty())` 只保证非空。数据文件扩充时会出现"词典里明明有这个词却打不出来"且无任何错误信号的静默错误，与 `AGENTS.md` §10.1"错误和空状态使用可判定模型，不以……吞异常代替处理"相悖。

**置信度**：confirmed-by-reading

**建议方向**：改为显式上界校验（超出即失败），或把上限提升为可配置并校验实际条目数。

### D3-8 硬编码中文 UI 字符串（上一轮只修了 `MainActivity` 的四处）

上一轮审计报告了 `MainActivity` 的四个 toast 并已改为资源引用。同类问题在**其他文件**依然存在。以下为全量扫描结果（已排除注释、kaomoji 数据、AI 提示词与键面符号，理由见 §6）：

| 文件:行 | 代码 |
| --- | --- |
| `app/.../settings/SettingsScreenView.kt:327` | `append(" · 不可用")` |
| `app/.../settings/SettingsScreenView.kt:337` | `contentDescription = "启用 ${pack.displayName}"` |
| `app/.../settings/SettingsScreenView.kt:340` | `commandButton(if (pack.selected) "使用中" else "使用")` |
| `app/.../settings/SettingsScreenView.kt:343` | `commandButton("删除")` |
| `ime-ui/.../ui/ZeroInputView.kt:275` | `languageButton.contentDescription = if (...) "敏感输入保护中" else "切换中英文"` |
| `user-data/.../SecureClipboardVault.kt:40` | `displayName = "安全片段 ${index + 1}"` |
| `user-data/.../SecureClipboardVault.kt:261` | `validateLabel` 空标签回退 `"私密片段"` |

`contentDescription`（`:337`、`ZeroInputView.kt:275`）是**无障碍描述**，在英文 locale 下会被读屏软件用中文朗读。

另需注意 `SecureClipboardVault` 位于 `user-data` 模块，而 `AGENTS.md` §5.1 规定该模块"不应承担界面"——用户可见文案出现在纯数据模块中本身即越界。

**已验证**：`values/strings.xml` 与 `values-en/strings.xml` 键完全一致（各 219 个键，无缺失，无非专有名词的未翻译项），因此问题**只在代码字面量**，不在资源文件。

**置信度**：confirmed-by-reading

---

## 4. 按键热路径与资源开销汇总

| 位置 | 问题 | 触发频率 | 线程 |
| --- | --- | --- | --- |
| `language-pack/.../LanguagePackEngine.kt:121-128` | 全表 5 万键线性前缀扫描 + 序列/列表分配 | **每次击键** | IME 主线程 |
| `ClipboardGuardRuntime.kt:142-156` | 队列拒绝后每 50 ms 轮询重试，无退避/上限 | 队列饱和期间 | IME 主线程 |
| `user-data/.../EmojiHistoryRepository.kt:25-34` | 读盘 + 全量重写 + `maxOf` 全表扫描 | 每次 emoji 提交 | 后台 worker |
| `engine-dictionary/.../ReferenceDictionary.kt:31-34` | 为每条读音构造 1..n 全部前缀 | 引擎创建一次 | 后台 worker |
| `engine-english/.../EnglishInputEngine.kt:214-222` | 为词表每个词构造全部前缀 | 引擎创建一次 | 后台 worker |
| `AppGraph.kt:41-44` | `engineExecutor` 队列容量仅 2 | 冷启动 / 会话快速切换 | — |

`AppGraph.kt:41-44` 的容量 2 同时也是 D2-2 的触发条件：它让"初始化任务被拒绝"从理论问题变成可复现的边界条件。

**代码规模**：`ZeroInputService.kt` 已从上一轮记录的 1,542 行增长到 **1,629 行**，超过 `AGENTS.md` §10.2 的 1,500 行软上限（该文件是上一轮 L7 标记的"最可能藏下一个生命周期缺陷"的文件）。`InputSessionController.kt`（809 行）、`QueuedPersonalizationStore.kt`（489 行）在限内；单函数未发现超过 100 行的实现。

---

## 5. 经核实为正确的部分

负向结论同样记录，便于后续回归时确认边界。

**密码学与密钥**

- `AesGcmEnvelope`：格式 1 头、IV 长度校验（`MIN_IV_SIZE=12`、`MAX_IV_SIZE=32`）、128-bit tag、AES-256-GCM、随机 IV，无固定 IV 或自研算法。
- `AesGcmKeyStore`：读取路径使用 `existingKey()` 而非 `getOrCreateKey()`——**不可读的文件绝不重建替代密钥**，符合"密钥丢失即数据不可恢复"的预期。
- `SecurityAliases`：用户词库、导出、emoji 历史、表情、安全剪贴板正文/索引、AI 配置/会话**各用独立别名**，无共享密钥。
- `AuthenticationGrant`/`AuthenticationLifetime`：基于 `SystemClock.elapsedRealtime()`、单次消费、硬上限 30 s，调用方只能缩短不能延长。

**隐私与平台边界**

- 备份：`allowBackup="false"`，`backup_rules.xml` 与 `data_extraction_rules.xml` 对全部 9 个 domain 排除，密文位于 `noBackupFilesDir/encrypted/`。
- Manifest：仅 `MainActivity`（launcher）与 `ClipboardImportActivity`（`text/plain` 的 `ACTION_PROCESS_TEXT`/`ACTION_SEND`）导出；IME service 由 `BIND_INPUT_METHOD` 保护；无多余权限；`usesCleartextTraffic="false"` 且 `network_security_config` 全局禁明文。
- `FLAG_SECURE`：AI 草稿、手写、安全剪贴板解锁、剪贴板导入、安全剪贴板管理、用户词库、表情管理、密码与配置对话框共 17 处，覆盖完整无遗漏。
- AI 网络边界：仅 HTTPS（校验 `scheme`/`host`/`userInfo`/`query`/`fragment`/`port`）、`instanceFollowRedirects = false`、请求体 ≤256 KiB、流/单行/输出均有上界、`finish_reason` 必须为 `stop`、错误文本不含响应体或密钥。
- 语言包导入边界：归档 ≤128 MiB、条目 ≤2049、清单 ≤256 KiB、单文件 ≤32 MiB、总量 ≤256 MiB；`actual.size == actual.distinct().size` 拒绝重名；`PackPathPolicy` 拒绝绝对路径/盘符/空白段/`.`/`..` 并以 `canonicalFile` + 前缀校验防 zip slip；扩展名白名单排除脚本与 native 库；先 `staging` 后原子 `renameTo` 并带回滚；逐文件 SHA-256；`LanguagePackJson` 用严格扫描器在交给 `org.json` 前完成深度/重复键/尾随数据/数字/转义预检。
- `privacyCheck` 中做对的部分：扫描 `subprojects/*/src`（含 `test`/`androidTest`）、解析 debug+release 合并清单、权限白名单为**精确匹配**而非前缀匹配、缺失清单直接判为违规。

**线程与阻塞**

- 全仓库（含测试）无 `runBlocking`；主源码中无 `Thread.sleep`、`println`、`printStackTrace`。
- `QueuedPersonalizationStore.clearAndAwait()` 的 `task.get()` 唯一调用点是 `MainActivity.operations.execute`（后台路径），未落在 IME 输入线程。
- 有界执行器全部使用 `ArrayBlockingQueue` + `AbortPolicy`，拒绝行为对调用方可见（D2-2 的例外是唯一未处理的提交点）。

**上一轮补丁中确认正确的部分**

- `ClipboardClearActivity.focused` 改为单实例（`AtomicBoolean` → `val` + `set(true)`），原先"谓词恒为 false 导致清理永不执行"的缺陷已消除（但缺测试，见 D2-7）。
- `ClipboardGuardNotifications` 补上 `PendingIntent.FLAG_UPDATE_CURRENT`，陈旧 ticket 复用问题已消除。
- `EditorPrivacyPolicy` 现已把 `TYPE_TEXT_VARIATION_FILTER` 与 `TYPE_TEXT_FLAG_AUTO_COMPLETE` 归入 `isIdentifierField`（`EditorPrivacyPolicy.kt:86-93`），并有对应负向测试（`EditorPrivacyPolicyTest` 的"filter and autocomplete editors never use personalization"）。
- `ClipboardGuardRuntime` 不再在调用线程内联执行 `refresh()`（方向正确，但引入 D2-5）。
- `MainActivity` 四处 toast 已资源化，且 `values`/`values-en` 键完全对齐。

---

## 6. 已排除：判定为"设计如此"的项

以下项在初审中出现，经与文档和 ADR 核对后**判定为有意设计，未计入缺陷**。列出以备复核。

| 项 | 排除理由 |
| --- | --- |
| 安全剪贴板面板显示"安全片段 N"而非用户标签 | 面板与独立索引刻意**不含标签与正文**，标签只在已认证的管理页可见（`SecureClipboardManagerActivity.kt:106-192`）。这是隐私设计，不是缺陷。（其中的中文字面量问题仍计入 D3-8） |
| `AppGraph.close()` 在生产中永不执行（`Application.onTerminate()` 真机不回调） | 属"进程寿命 == 应用寿命"的既有模型，初审已注明"对 IME 尚可接受"。是潜在风险而非现存缺陷，建议在威胁模型中记录，不列为待修项 |
| 键面按钮显示"中"/"En"（`KeyboardPanel.kt:34`、`ZeroInputView.kt:140`、`:535`） | zh-first 输入法的键面符号，紧凑且惯用。仅其无障碍描述计入 D3-8 |
| `InputSessionController.handle` 每次命令都清理词联想缓冲 | `docs/architecture.md:240-244` 明确"direct insertion and engine boundaries wipe the buffer"，是有意失效策略 |
| `WordAssociationIndex.suggest()` 每次查询最多 32 次子串分配 | 落在 `docs/architecture.md:225-230` 声明的"at most 32 suffix probes"预算内 |
| `SecureClipboardVault` 缓存在内存中的无正文索引、`indexCache` | `docs/architecture.md:297-312` 与 ADR 0006 明确该索引不含正文与标签，可缓存 |
| AI 提示词使用中文字面量（`OpenAiCompatibleProvider.kt:138`、`:140`） | 发给模型的行为指令，非 UI 文案，与 locale 无关 |
| `TYPE_CLASS_NUMBER`/`PHONE`/`DATETIME` 在某些变体下按未知编辑器处理 | `AGENTS.md` §3.4 要求的"保守默认值、无法确定按敏感处理" |
| `prepareModelAssets` 在资产缺失时使构建失败 | `AGENTS.md` §9 与 `docs/model-integration.md` 明确要求"构建在固定资产缺失或哈希不符时失败"，是刻意设计（缺的是文档步骤，见 D3-2） |

---

## 7. 建议的处理顺序

1. **D1-1** —— native 生命周期与崩溃路径，安全边界与稳定性优先级最高（`AGENTS.md` §1 决策顺序第 1、3 项）。
2. **D2-1** —— 四个仓库的 `deletionPending` 加 `finally`，改动小、影响大，且是上一轮遗留项。
3. **D2-2** —— `AppGraph` 初始化加拒绝保护，一行级改动，消除启动崩溃可能。
4. **D2-3 / D2-4** —— 门禁强度属安全边界（§1 决策顺序第 1 项），建议与 `testPrivacyBoundary` 的负向用例一起改。
5. **D2-5 / D2-6** —— 剪贴板防护的重试链与 receiver 泄漏。
6. **D2-7** —— 补 `ClipboardClearActivity` 确认按钮的回归测试。
7. **D3-2** —— 文档缺失会导致新克隆无法构建，影响所有后续贡献者。
8. 其余中等项按维护节奏处理；D3-1 与 D3-4 建议直接做减法（删死代码/删未接线功能）而非补实现。

---

## 8. 未验证项与本次审查的局限

**本次未执行任何构建、单元测试、`privacyCheck`、Android Lint、`requireRime` 构建或真机/模拟器验证。** 所有结论均来自源码阅读与少量字节级/命令级实测（`git ls-files`、`git check-ignore`、网络与剪贴板正则行为）。D1-1、D2-1、D2-2、D2-5、D2-6 的**可观察症状**（崩溃、持续失败、主线程轮询、receiver 泄漏）需要设备或构建才能确认。

**未审查范围**：

- `third_party/**`（librime、Boost、OpenCC、LevelDB、yaml-cpp、marisa-trie）。
- `engine-rime/src/main/cpp` 的 C++/JNI 实现——JNI 边界仅从 Kotlin 侧核对，未审查 `zero_rime_jni.cpp`（本地引用释放、异常检查、边界参数校验、native 内存所有权）。
- `model-scoring` 与 `ime-ui` 未逐行审查（约 18 + 48 个文件）：仅覆盖 `CandidateItemView`、`SecureClipboardPanelView`、`ZeroInputView` 的部分区段及构建期资产校验逻辑。除 D3-3、D3-8 外可能仍有热路径问题。
- `tools/*.py` 评价与准备脚本、`build/**` 生成产物、PowerShell 打包脚本的内部逻辑（仅核对了它们是否调用必需的准备步骤）。
- 未审查各 ABI 的 native 打包结果、`-PrequireRime=true` 的实际行为、Release 混淆规则对 JNI 类的保留情况。

**建议**：修复前先按 `AGENTS.md` §11.1 选定最低验证要求。D1-1、D2-1、D2-2 属"加密、用户数据、认证或异步队列"与"JNI、librime"类改动，需要负向测试 + 竞态/代次测试 + native 失败降级验证；D2-3、D2-4 属 Manifest/权限/剪贴板类改动，需要 `privacyCheck` + Debug/Release 合并清单检查 + `:app:lintDebug`。

---

## 9. Follow-up（2026-10-02）

本轮复核与回归测试已处理以下条目：

- D1-1：`RimeRuntime` 现在为初始化在途工作保留代次所有权，关闭或失败时在安全边界收敛 native finalize，并让会话释放回调归还活动计数。
- D2-1：用户词库、表情、个人表达和安全剪贴板清除路径会分别尝试删除数据与导出密钥；失败状态保持 fail-closed，后续操作不会访问不完整数据。
- D2-2：`AppGraph` 的冷启动引擎任务提交增加执行器拒绝保护。
- D2-5/D2-6：剪贴板防护刷新采用有界退避重试；悬浮窗分别跟踪 receiver 与 AppOps 注册状态，异常路径会清理已成功注册的资源。
- D3-5/D3-7：表情时间戳使用高水位值，参考词典超限改为显式失败；语言包前缀索引限制每个前缀的唯一候选数量。
- D2-7：补充了 `ClipboardClearActivity` 的确认清理单元回归覆盖。

已运行对应模块单测、根级 `testDebugUnitTest`、`privacyCheck`、Debug Lint、`-PrequireRime=true` Debug 构建，以及正确包名的 `HandwritingCoordinatorTest`。`GlideEndToEndTest` 在模拟器上仍在候选展示等待阶段超时，详见交付说明。
