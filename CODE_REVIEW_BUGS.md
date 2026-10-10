# ZeroInput 代码复查报告

> 本文档只报告**当前存在的问题**。每轮全面复查后重写；已修复/不成立项不再保留。
> 最近更新：2026-10-10（第五轮）。HEAD `2f168a5` + 约 134 个未提交文件，按当前工作区审查。

## 结论概览

| 严重度 | 数量 | 编号 |
| --- | ---: | --- |
| 高危 | 0 | — |
| 中危 | 2 | M1、M2 |
| 低危 | 10 | L1–L10 |
| 性能 / 观察 | 8 | P1–P8 |

---

## 中危

### M1. AI/搜索草稿的预热引擎结果在提交/关闭路径被永久挂起，草稿停在降级引擎

- 位置：`LocalDraftInput.kt:49`（`ready = { !input.state.snapshot.isComposing }`）+ `EngineWarmupResultDelivery.kt:79-91`（`deliverPending` 在 `!canDeliver` 时保留 `pending`）+ `ZeroInputService.kt:973-981`（`submitAiRequest` 不调 `resume`）与 `:893-899`（关闭面板时 `aiDraft.close()`）。
- 触发：打开 AI 面板 → 开始拼音组合（触发预热）→ 预热完成时草稿处于组合态 → 直接点"插入/提交"或关闭面板。`resume()` 只在按键类路径（`LocalDraftInput.kt:79-126`）调用，提交/关闭路径均不触达。
- 影响：Prepared 引擎滞留，草稿一直用降级拼音引擎（候选质量下降）。不泄漏资源（close/替换路径均 `closePrepared`，delivery 仅保留 1 个）。
- 修复方向：`deliverPending()` 判定 `!canDeliver` 时直接关闭 Prepared（让下次交互重新 warmup），或在提交/关闭路径补 `resume()`；前者更简单。

### M2. `prepareDictionariesIfIdle` 在每次词典/资源发布后强制 native 全量重初始化

- 位置：`RimeRuntime.kt:30-37`（`preparedDictionaryRevision != dictionaryRevision.get()` 时 `close()` 后 `initialize()`）+ `:273-289`（`close()` 不重置 `preparedDictionaryRevision`）+ `AppGraph.kt:174-175`（`onPublished` 递增 `dictionaryRevision`）。
- 触发：安装/更新任何公共词典或可选资源 → `dictionariesChanged()` → 下次中文预热发现 revision 不等 → `close()` 已 READY 的 runtime → `initialize()` 全量重跑（native 初始化 + 会话验证，秒级 worker 阻塞）。
- 影响：磁盘重建有短路（`RimeAssetInstaller.kt:68` identity 未变时 `.installed` 已存在直接返回），但 **native finalize + 全量重初始化 + 会话验证** 必然执行，即使资产 identity 完全未变。首个中文会话前一次确定性的可感知延迟。
- 修复方向：`install()` 返回与当前 `workingDirectories` 相同版本目录时跳过 native 重初始化；或将失效粒度从"任何 onPublished"细化到"资产 identity 实际变化"。

---

## 低危

- **L1. 失效草稿预热任务的 result 消息滞留主线程消息队列** — `LocalDraftInput.kt:135-147`：`close()` 后已 post 的 `deliverPending` Runnable 无 identity 无法 `removeCallbacks`，滞留队列直到自然执行（执行时被丢弃，无错误行为）。修复：给 delivery Runnable 挂 token，close 时按 token remove。
- **L2. association 候选在 predictor 迟就绪时静默缺席无重试** — `ZeroInputService.kt:284-288`：predictor 就绪时若无会话，之后新会话拿到的 Empty 实现联想永久缺席且无迟就绪重试（对照频率查询有 listener 补齐链路）。修复：`WordAssociationSession` 记录 predictor-ready 代次，空结果时代次变化则下次 committed 重建。
- **L3. EmojiCatalog.find 每次 emoji 提交全量线性扫描** — `ZeroInputService.kt:1195-1199`：`expressionIsCurrent` 对非自定义 emoji 调 `EmojiCatalog.find(entry.value)` 全目录比较（数百项），每次 emoji 上屏扫一遍。修复：value→entry 惰性 Map。
- **L4. ModelRankingCoordinator.deliver 缺 packageName 校验（纵深防御缺口）** — `ModelRankingCoordinator.kt:60-62`：deliver 只校验 `allowed()/!frozen/modelRevision`。现有四重闸已使窗口不可利用，仅建议补 packageName 比对作纵深。
- **L5. ClipboardImportActivity.save() 明文 String 双驻留堆** — `ClipboardImportActivity.kt:175-177`：`copyText()` 返回 `String`（`ClipboardImportRequest.kt:24`），`vault.add` 内部又 `String(value)` 再建一份（`SecureClipboardVault.kt:104`）。明文以两份不可 wipe 的 String 驻留堆至 GC。修复：`vault.add` 提供 CharArray 重载供此路径直接消费。
- **L6. AndroidSystemClipboard 8.x 分支 ownEmptyTimestamp 可能捕获旧 clip 时间戳** — `AndroidSystemClipboard.kt:27-28`：`setPrimaryClip` 后立即读 timestamp，若系统尚未刷新（Binder 异步）读到旧 clip 值，ownEmptyTimestamp 被污染，下次 `timestamp()` 恒真导致空 clip 误判（fail-safe 空转，无泄漏）。修复：clear() 后不立即捕获，改为下次 timestamp() 惰性捕获。
- **L7. zero_rime_jni.cpp ToUtf8 对孤立低代理项输出畸形 CESU-8** — `zero_rime_jni.cpp:41-61`：孤立低代理（0xdc00-0xdfff 无前置高代理）原样编码而非 U+FFFD。两个用户输入入口（nativeSetInput/schema_id）已有 cpp 白名单拦截，无可达路径。修复：遇孤立低代理输出 0xef 0xbf 0xbd。
- **L8. publish-resources.yml torch==2.12.0 与导出脚本 weights_only 兼容性缺口** — `publish-resources.yml:23`：torch 2.6 前后 weights_only 反序列化行为有破坏性变化，若 2.12.0 拒绝旧 `.bin` 权重 pickle 类型，干净 CI 上 prepare-mini 可能失败（可复现性缺口）。修复：固定 torch 到已验证范围或验证 2.12.0 兼容。
- **L9. publish.py 本地回退 git credential fill 取凭据扩大凭据面** — `publish.py:21-25`：仅本地手动运行且缺 GH_TOKEN 时触发 `git credential fill`。已禁交互+超时，但扩大凭据面。修复：无 GH_TOKEN 时直接报错要求显式提供。
- **L10. public-dictionaries.yml force-with-lease 可被跨 scheduled run 覆盖人工推送** — `public-dictionaries.yml:41`：两个 scheduled run 错开时后跑基于新 checkout，force-with-lease 校验远端旧值，人工推送会被静默覆盖（机器人专用分支，面窄）。修复：push 前校验远端分支 tip 归属。

---

## 性能与结构观察（不构成缺陷）

- **P1 · 语言包前缀索引内存放大**：`LanguagePackEngine.buildPrefixIndex`（`:132-144`）为每个 shortcut 每个前缀建 bucket，受 MAX_ENTRIES=50_000/MAX_TOTAL_TEXT_BYTES=32MB/shortcut≤64 约束有界。补充：`bucket.size >= MAX_CANDIDATES(8)` 提前 break 按字典文件行序截断（`:138-139`），前 8 个词条永久占满该前缀，后续更高频词条被丢弃——候选质量非最优但功能正确。扩大词典上限前需重新评估（限制索引前缀长度 + 排序依据）。
- **P2 · AI 上下文视图全量重建**：`AiContextView.render`（`:29`）每次 `removeAllViews()` 重建（≤16 引用+≤20 消息），有界。上限扩大前应改差量更新。
- **P3 · 会话转写全量拼接**：`AiWorkbenchPanelView.renderConversation`（`:264`）每次 `joinToString` 重建整个转写（≤20 消息×32k 字符），有界低频。
- **P4 · KeyboardLayoutHost.onMeasure 每次 currentWindowMetrics**：`KeyboardLayoutHost.kt:174-176`：binder 开销，面板展开/收起/候选变化时触发。建议缓存 windowHeight 仅在 onSizeChanged/配置变化时刷新。
- **P5 · SecureClipboardVault 全局 synchronized**：Keystore/磁盘抖动时放大锁占用。B5 修复后常规持锁写入已消除（仅索引不一致时重写），锁占用显著降低，维持结构观察。
- **P6 · commitEmoji 主线程串行 BoundedExecutors.purge** — `ZeroInputService.kt:1150`：队列容量仅 2，代价可忽略；仅记录该点位于按键路径。不建议改。
- **P7 · onStateChanged 每次 publish 都调 renderEngineStatus** — `ZeroInputService.kt:441`：内部 5 个 render* 调用+全量状态分支（`:1694-1711`）在每个按键 publish 都执行；若视图侧无短路，这是每键一次的冗余渲染链，建议视图侧加等价短路。
- **P8 · SecureClipboardManagerActivity 删除确认对话框将条目 label 明文放入 message** — `SecureClipboardManagerActivity.kt:235`：对话框受 FLAG_SECURE 保护且 label 非正文，需用户确认，风险可控。若威胁模型将 label 视为敏感元数据，应改为通用文案。

---

## 建议处理优先级

1. **M1**（一行修复：`!canDeliver` 时关闭 Prepared）→ 2. **M2**（资产 identity 未变时跳过 native 重初始化）→ 3. **L5**（vault.add 补 CharArray 重载）→ 4. **L6**（8.x 时间戳惰性捕获）→ 5. **L8**（固定 torch 版本）→ L1/L2/L3/L4/L7/L9/L10 与 P1–P8 视后续演进再议。

---

## 验证

- 单元测试 + privacyCheck：**BUILD SUCCESSFUL**（262 tasks）。命令：

```powershell
./gradlew.bat testDebugUnitTest :ai-api:test :engine-api:test :engine-english:test `
  :engine-dictionary:test :language-pack:test :model-scoring:test privacyCheck --no-parallel
```

- **未运行**：androidTest/模拟器/真机、`:app:lintDebug`、`-PrequireRime=true` Rime 源码验证、Release APK 构建。
- 核心安全防线（密码学、两阶段粘贴、系统剪贴板隔离、AI 网络边界、无障碍最小化、语言包导入、XZ 词典校验、热路径线程纪律、构建供应链、资源双语一致性、数据清除、日志）经逐条读码复核全部在位，详见第五轮完整审查（本文档重写前的历史版本）。
