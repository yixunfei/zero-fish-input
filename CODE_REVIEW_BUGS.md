# ZeroInput 代码复查报告（修复核对）

本轮对上一版报告（中危 7 / 低危 12，共 19 条）逐条回源码核对修复结果。

**汇总：已修复 8 / 部分修复 3 / 未修复 7 / 错误修改 1（需修正）。**

## 待修复（未修复 7 条 + 错误修改 1 条）

### 中危

**0. EmojiHistoryRepository.clear() 修复引入语义错误：finally 无条件复位 deletionPending，破坏 fail-closed**
- 位置：`user-data/src/main/kotlin/dev/zeroinput/userdata/EmojiHistoryRepository.kt:50-65`
- 状态：**错误修改（需修正）**。上一轮为修复"clear 失败后 deletionPending 永久卡死"而在 finally 加了 `deletionPending = false`：
  ```kotlin
  try { store.delete(deleteKey = true); lastUsedHighWater = 0L; deletionPending = false }
  catch (error: Throwable) { deletionPending = true; throw error }
  finally { deletionPending = false }   // 无条件复位，覆盖 catch 里的 = true
  ```
- 为什么是错误：finally 无条件 `deletionPending = false` 会**覆盖 catch 中设的 `true`**。当 `store.delete` 抛异常（Keystore deleteEntry 失败、文件残留）导致数据可能处于半删除状态时，后续 `load()`/`recordIfRevision` 不再 fail-closed，而是放行去操作一个可能损坏/不一致的加密 store。原本"失败后拒绝访问"的安全语义被改成了"失败后照常读写"。这违背了其他 Repository（PersonalExpression/UserLexicon/SecureClipboardVault）在 clear 失败后保持 fail-closed 的一致设计，也违反 AGENTS.md"安全或隐私要求…不得削弱"与"清除数据…失败关闭"的强制要求。
- 正确修复方向：finally 不应无条件复位。要同时解决"卡死"与"fail-closed"，应改为：删除**成功**才 `deletionPending = false`；删除**失败**保持 `deletionPending = true`（fail-closed），并提供一个显式的重试/恢复入口（或允许 clear 重试——重试成功路径自然复位）。即去掉 finally，保留 try 成功路径的 `deletionPending = false`，catch 保持 `= true` 并确保 clear 可重试。这正是原报告的修复建议，当前实现误用了 finally。

**1. PersonalCandidatePaging.changePage 的 PREVIOUS 在混合第 0 页行为依赖引擎分页，语义错位**
- 位置：`ime-core/src/main/kotlin/dev/zeroinput/ime/core/PersonalCandidatePaging.kt:29-41`、`ime-core/src/main/kotlin/dev/zeroinput/ime/core/InputSessionController.kt:189-193`
- 状态：**未修复**。`changePage` 与上一轮一致，`changePage(PREVIOUS)` 仍只在 `pages.firstKey() > 0` 时接管，回退到第 0 页后按"上一页"返回 false 落到引擎分页。`publish()` 仍把个人候选前置、`hasPreviousPage = !includesNative || native.hasPreviousPage`。
- 影响：个人词组翻页浏览后回退到第 0 页再按"上一页"——引擎 `hasPreviousPage=false` 时按钮显示不可用（与浏览中途不一致）；为 true 时跳到引擎历史页而非个人候选上一页。分页语义错位。

### 低危

**2. UserLexiconRepository.exportJson() 返回明文字节数组不清零**
- 位置：`user-data/src/main/kotlin/dev/zeroinput/userdata/UserLexiconRepository.kt:200-202`
- 状态：**未修复**。仍直接返回未清零明文。仅 androidTest 使用（`UserLexiconBoundaryTest.kt:236,250`），app 主代码未调用，潜在而非现行风险。

**3. EmojiHistoryRepository.recordIfRevision() 写入失败时 lastUsedHighWater 未回退**
- 位置：`user-data/src/main/kotlin/dev/zeroinput/userdata/EmojiHistoryRepository.kt:28-35`
- 状态：**未修复**。仍先 `lastUsedHighWater = nextLastUsed`（29）再 `store.write`（35），write 失败或 34 行代次校验 `return false` 时 high-water 已抬高。仅时间戳跳空，不影响正确性。

**4. ClipboardImportActivity 标签明文走 View 自动 saved-state，存活期超过草稿本身**
- 位置：`app/src/main/kotlin/dev/zeroinput/ime/clipboard/ClipboardImportActivity.kt:90-93`、`app/src/main/kotlin/dev/zeroinput/ime/clipboard/ClipboardImportView.kt:34`
- 状态：**未修复**。`onSaveInstanceState` 仍只 `outState.clear()`，`labelInput`（TextInputEditText）未加 `setSaveEnabled(false)`。标签明文仍会被框架在 super 调用内部序列化进系统 Bundle。
- 修复方向：给 labelInput 显式 `setSaveEnabled(false)`。

**5. 「复制选中文字」在导入页认证挂起期间再次触发时，新草稿被静默丢弃**
- 位置：`app/src/main/kotlin/dev/zeroinput/ime/clipboard/ClipboardImportActivity.kt:84-88`、`ClipboardSelectionImportActivity.kt`
- 状态：**未修复**。父类 `onNewIntent` 仍直接 `discardAndFinish()`（87），`ClipboardSelectionImportActivity` 未重写 onNewIntent 消费新 token。第二次复制经 singleTask 路由回旧实例仍被直接 finish，新草稿无人 take。
- 修复方向：子类重写 onNewIntent 消费新 token，或 `copySelectedText` 启动前显式 finish 既有实例。

## 部分修复（3 条，残留风险需跟进）

### 中危

**6. JSONObject(String(bytes)) 解密路径明文副本——加了 `encoded = ""`，但不清零底层 char[]**
- 位置：`user-data/.../AiConversationRepository.kt:71-73`、`SecureClipboardVault.kt:164-166,202-204`、`AiConfigurationRepository.kt:96-98`
- 状态：**部分修复（修复无效）**。三处都在 `JSONObject(encoded)` 后加了 `encoded = ""`，但这只是让变量指向空字符串字面量，**原明文 String 的底层 `char[]` 仍残留在堆里**直到 GC。`finally { bytes.fill(0) }` 只清零 byte 数组。apiKey、AI 会话内容、剪贴板正文的 String 副本问题未解决。
- 修复方向：JSON 路径改用可在 finally 清零的 `CharArray`/`CharBuffer` 解析（如 `JsonReader` 读 `CharSequence` 包装），或明确接受 String 不可清零的边界并记录。

**7. SecureClipboardManagerActivity 添加条目明文——CharArray 已清零，但 concatToString 的 String 仍进认证回调**
- 位置：`app/src/main/kotlin/dev/zeroinput/ime/settings/SecureClipboardManagerActivity.kt:135-146,182`
- 状态：**部分修复**。已改 `toCharArray()`，`addItem` 后 `secret.fill('\u0000')`（146）清零 CharArray——这是改进。但 145 行 `secret.concatToString()` 产生的 String 仍被传入 `authenticate { grant -> addItem(...) }` 闭包，且 `addItem(label, value: String, ...)`（182）与 `vault.add` API 签名都是 String，String 副本不可清零、残留最长 30 秒。残留是 vault API 签名所限。
- 修复方向：`vault.add` 改为接受 `CharArray`/`ByteArray` 并在内部加密后清零；调用链全程避免 String。

## 已修复（9 条）

| # | 条目 | 证据 |
| --- | --- | --- |
| 中1 | 九键 Fallback 无法输入中文 | `FallbackPinyinEngine.kt:105` 新增 `nineKeyRepresentative(it)` 数字→字母映射 |
| 中2 | probe 与工作台共享 executor 饿死 | `AppGraph.kt:49-52` 新增独立 `aiProbeExecutor`，`MainActivity.kt:279` 已改用它 |
| 中3 | 导出 "wt" 覆盖无提示 | `strings.xml:110` 导出确认文案新增"选择已有文件会覆盖其内容" |
| 低7 | AiStreamDelivery 静默截断 Delta | `AiStreamDelivery.kt:25-27` 超限改为 `terminal = Failed("exceeded limit")` + `ended = true` |
| 低8 | abort() 误用 purge() | `OpenAiCompatibleProvider.kt:65` 改为 `BoundedExecutors.cancelQueued(executor)` |
| 低9 | SecureClipboardVault.read 副作用写 persistIndex | `SecureClipboardVault.kt:53-58` read() 不再调 persistIndex |
| 低11 | LanguagePackParser >2^53 精度丢失 | `LanguagePackParser.kt:56` 改为 `BigDecimal(...).toBigIntegerExact().longValueExact()` 精确转换 |
| 低12 | SecureClipboardUnlockActivity 缺 onNewIntent | `SecureClipboardUnlockActivity.kt:43-51` 新增 onNewIntent，完成旧 requestId 并消费新 intent |
| 低13 | ClipboardImportIntent clipData 过度收紧 | `ClipboardImportIntent.kt:26-32` 不一致时回退 `ClipboardImportText.parse(value)` 而非 return null |

## 复核后撤销的误报

以下子代理/历史发现经回源码核实后**不成立**，不计入待修：

1. **ClipboardGuardRuntime.refresh 竞态（报高危）**：不成立。`reconfigure()` 在 `refreshQueued` 之前就 `revision.incrementAndGet()`，旧会话 lease 立即失效。
2. **SecureClipboardVault 并发 clear/remove 复活（中危）**：不成立。remove 全程持锁 + clear 进锁先置 deletionPending，两种交错均被兜住。
3. **引擎失败降级丢组合串（中危）**：不成立。ime-core `adoptPreparedEngine`/`reloadEngineIfIdle` 有 `isComposing` 守门。
4. **PersonalExpressionRepository.clear() 不可恢复（中危）**：不成立。deletionPending 失败保持 true 是有意 fail-closed，clear 可重试。
5. **handleSecurePasteConsentResult editorToken 恒 0（历史已知项）**：不再适用。符号已不存在，现有 bind/confirm/editorChanged 多重 source 校验兜底。
6. **UserLexiconRepository.persist 代次过期清空快照（中危）**：不成立。与 clear() 语义一致，设计选择。
7. **历史已知项 (d) bindPasteConsent 提前作废（疑似）**：不成立。restart 序列 targetSession==null 时 leaveEditor 不 close，时序闭环。
8. **删除确认 dialog 显示秘密标签（报高危）**：不成立。管理页认证后列表本就显示 label，混淆了"键盘未认证索引"与"已认证管理页"边界。
9. **UserDictionaryActivity 删除确认显示词组正文（中危）**：不成立。已认证管理页本就显示 value，FLAG_SECURE 已加。
10. **候选条首候选无障碍逐键播报（中危）**：待确认设计口径，倾向有意设计，不计入确定 bug。

## 补充观察（非 bug，但值得记录）

- **已知项 (a) 的 UX 死路**：`SecurePasteConsent.authorize` 后 deadline 30s，`confirm` 拿到的 grant 也是 30s 一次性。第 29 秒点确认时，worker 真正 `vault.read` 的 `grant.consume()` 可能已过期返回 false，read 抛异常且 consent 已 cancel 不可重试。UX 级死路，非安全漏洞。

## 重点核查后确认无问题

- **两阶段粘贴状态机**：30s 双超时、删除代次、编辑器公开标识、会话令牌绑定、grant 一次性消费、old-IC 防护。
- **AES-GCM 加密**：IV 随机生成、AAD 分用途隔离、8 个密钥别名独立。
- **代次失效**：各 Repository 的 clear 先递增代次再等锁。
- **退出路径清理**：selectionReader、engineWarmup、drafts、panels、reconversion 全覆盖。
- **引擎边界**：JNI 白名单 fail-closed、ExpandingRimeEngine 扩展状态机、RimeRuntime 生命周期、GlideCoordinator 双校验。
- **语言包 ZIP/JSON/路径防线**、**隐私门控**、**AI 边界**（HTTPS 强制/禁重定向/去敏/附件清零）。

## 验证说明

本轮为静态源码核对，逐条比对上一版报告的 19 条发现与当前代码，未运行全量单测。完整验证仍应按 AGENTS.md §12 执行 `testDebugUnitTest privacyCheck :app:lintDebug` 及 Release 命令。

**建议优先跟进**：中危 #0（EmojiHistory clear 的 finally 无条件复位破坏 fail-closed，**最优先**——删除失败后照常读写可能损坏的 store，是错误修改需回退 finally）→ 中危 #1（PersonalCandidatePaging 分页错位）→ 部分修复 #6/#7（明文 String 副本，`encoded = ""` 是无效清零需改 CharArray 解析）→ 低危 #4/#5（两条 singleTask/saved-state 草稿边界）。
