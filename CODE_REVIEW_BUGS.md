# ZeroInput 代码复查报告

本轮对全部模块（app、ime-core、ime-ui、engine-api、engine-english、engine-rime、engine-dictionary、user-data、security、language-pack）做了独立复查，所有发现均已回源码核实触发路径。与历史记忆交叉比对：旧报的高危/中危绝大多数已修复且无回退。

下面只列**本轮确认的待修 bug**，按严重度排序：**中危 7 / 低危 11，无高危**。文末附"复核后撤销的误报""补充观察"与"重点核查确认无问题"三节。

所有子代理发现均经父代理回源码逐条复核，8 条误报已列入"撤销的误报"一节并给出代码证据。

## 中危

### 1. 九键布局下 FallbackPinyinEngine 完全无法输入中文
- 位置：`engine-rime/src/main/kotlin/dev/zeroinput/engine/rime/FallbackPinyinEngine.kt:103-113`、`engine-rime/src/main/kotlin/dev/zeroinput/engine/rime/RimeEngineFactory.kt:28-29`、`app/src/main/kotlin/dev/zeroinput/ime/AppGraph.kt:204`
- 触发：用户设置九键布局，且 native 引擎未就绪或初始化失败（冷启动首帧必经、native 加载失败、Release 缺 native）。此时 `createFallback(settings.chineseInputOptions)` 原样透传含 `keyboardLayout == NINE_KEY` 的 options。但 `character()` 只接受 `a-z`、`'`、微软双拼 `;`，九键用户按 `2`-`9` 走 `commitLiteral(text)` 把数字直接提交给编辑器。
- 影响：降级模式下九键用户无法输入任何拼音，数字键直接上屏。`RimeInputEngine.kt:75` 证明九键数字是合法输入（native 路径显式接受 `'2'..'9'`），fallback 未做对应映射。
- 修复方向：Fallback 对 NINE_KEY 布局把数字键映射到对应字母组（`NineKeyReadings.digitFor` 已有映射表），或对 NINE_KEY 拒绝创建并显式进入失败态而非静默上屏数字。

### 2. 设置页 probe 与 AI 工作台共享容量 1 单线程 aiExecutor，长 SSE 流确定性饿死模型探测
- 位置：`app/src/main/kotlin/dev/zeroinput/ime/AppGraph.kt:45-48`、`app/src/main/kotlin/dev/zeroinput/ime/settings/MainActivity.kt:276`
- 触发：用户在编辑器打开 AI 工作台提交一个长流式请求（SSE 最长可挂到 `timeoutMs` 上限 120s），期间进设置页点"检测模型"。probe 与工作台请求挤同一个容量 1 队列，probe 排队等待，其自身 15s deadline 先到被 abort 报超时。
- 影响：设置页模型检测在工作台有活动请求时系统性误报失败，用户无法判断配置是否正确。确定性可复现，非概率性。
- 修复方向：probe 用独立 executor，或与工作台请求按代次互斥。

### 3. UserDictionaryActivity 导出用 `"wt"` 截断模式，覆盖已有导出文件无提示
- 位置：`app/src/main/kotlin/dev/zeroinput/ime/settings/UserDictionaryActivity.kt:131`
- 触发：导出用户词库时 `CreateDocument` 用 `openOutputStream(uri, "wt")` 截断写。若用户在文件选择器里选了已存在的导出文件，确认对话框只提示明文风险，未提示将覆盖现有文件。
- 影响：旧备份被静默覆盖，违反 AGENTS.md §3.3"导出范围、格式、明文风险和覆盖行为必须明确"的规约。数据破坏限于用户自己选择的导出目标。
- 修复方向：检测到目标已存在时在确认对话框明确提示将覆盖。

### 4. SecureClipboardManagerActivity "添加条目"把明文 String 捕获进认证回调，明文残留窗口拉长到 30s
- 位置：`app/src/main/kotlin/dev/zeroinput/ime/settings/SecureClipboardManagerActivity.kt:134-144`
- 触发：管理页"添加条目"→输入明文→点保存→完整明文 `secret`（String）被捕获进 `authenticate { grant -> addItem(...) }` 闭包→AuthenticationBroker 把该回调存入静态 map 最长 30 秒。String 不可清零（区别于 CharArray/ByteArray 可 `fill(0)`），残留堆中直到 GC。
- 影响：违反 AGENTS.md §4"解密出的明文…临时副本应缩短生命周期"。明文内存残留窗口从一次加密调用拉长到整个认证窗口。
- 修复方向：明文先转 `CharArray`/`ByteArray` 并在 vault.add 完成后立即清零；或缩短闭包持有明文的范围。

### 5. EmojiHistoryRepository.clear() 失败后 deletionPending 永久卡死，emoji 历史功能瘫痪
- 位置：`user-data/src/main/kotlin/dev/zeroinput/userdata/EmojiHistoryRepository.kt:50-63`
- 触发：`clear()` 进锁后置 `deletionPending = true`，`store.delete(deleteKey = true)` 抛 `IOException`（文件残留或 Keystore deleteEntry 失败）时，catch 块把 `deletionPending = true` 后重抛，但**没有 finally 块复位**。
- 影响：`deletionPending` 永久为 true，此后 `recordIfRevision`/`recent`/`load` 全部走 `load()` 第一行（66）的 `if (deletionPending) throw STORAGE`，用户清除数据后 emoji 历史永远不可用，只能杀进程恢复，无重试入口。数据已删但功能瘫痪。
- 修复方向：catch 后补 finally 复位，或提供重试入口；与其他 Repository 的可恢复语义对齐。

### 6. PersonalCandidatePaging.changePage 的 PREVIOUS 在混合第 0 页行为依赖引擎分页，语义错位
- 位置：`ime-core/src/main/kotlin/dev/zeroinput/ime/core/PersonalCandidatePaging.kt:29-41`、`ime-core/src/main/kotlin/dev/zeroinput/ime/core/InputSessionController.kt:189-193`
- 触发：`publish()` 把个人候选第 0 页与引擎候选**拼接前置**（`rows(value) + native.candidates`），并将 `hasPreviousPage` 置为 `!includesNative || native.hasPreviousPage`。但 `changePage(PREVIOUS)` 只在 `pages.firstKey() > 0` 时接管，回退到第 0 页后再按"上一页"一律返回 false，落到 `candidateWindow.changePage`（引擎分页）。
- 影响：个人词组翻页浏览后逐页回退到第 0 页再按"上一页"——若引擎 `hasPreviousPage=false`（常见，引擎候选只一页），上一页按钮因 `includesNative=true` 显示不可用，与浏览中途的可用状态不一致；若引擎为 true，则跳到引擎历史页而非个人候选上一页，候选集整体切换而非个人候选回退。属分页语义错位。需多页个人词组+回退到底才触发。
- 修复方向：第 0 页时 `hasPreviousPage` 不应依赖引擎分页状态；或 PREVIOUS 在第 0 页明确返回 false 且不落到引擎分页。

### 7. JSONObject(String(bytes)) 解密路径明文副本未清零，跨多个 Repository
- 位置：`user-data/src/main/kotlin/dev/zeroinput/userdata/AiConversationRepository.kt:67-94`；同类在 `AiConfigurationRepository.load()`（apiKey 明文）、`SecureClipboardVault.load()`/`loadIndex()`（剪贴板正文）
- 触发：`store.read()` 返回明文 `bytes` → `String(bytes, UTF_8)` 构造 JSON 串 → `JSONObject(String)` 解析 → `finally { bytes.fill(0) }`。`String(bytes,...)` 内部复制新 `char[]`，`JSONObject` 又产生 HashMap/String 明文对象；`finally` 只清 `bytes`，不清 String 副本与 JSONObject 内部明文。
- 影响：AI 会话内容、apiKey、剪贴板正文在内存留下不可清零的明文副本直到 GC，扩大内存转储/冷启动攻击面。与 `UserLexiconFormat.parse`/`EmojiHistoryFormat.decode`/`PersonalExpressionFormat.decode` 严格清零 `CharBuffer.array()` 的做法不一致，违反 AGENTS.md §4 明文生命周期强制要求。
- 修复方向：JSON 路径改用可在 finally 清零的 `CharArray`/`CharBuffer` 解析，或接受 String 不可清零的边界并缩短副本存活范围。

## 低危

### 8. UserLexiconRepository.exportJson() 返回明文字节数组不清零
- 位置：`user-data/src/main/kotlin/dev/zeroinput/userdata/UserLexiconRepository.kt:200-202`
- 说明：与 `exportEncrypted()`（205-212 行，有 `plaintext.fill(0)`）形成对比，`exportJson()` 直接返回未清零的明文 JSON 字节数组。当前仅在 androidTest 使用（`UserLexiconBoundaryTest.kt:236,250`），app 主代码未调用，故为潜在而非现行风险。一旦未来接入 UI 导出即违反明文生命周期强制要求。

### 9. SecureClipboardVault.read() 在条目不存在时触发 persistIndex() 写入
- 位置：`user-data/src/main/kotlin/dev/zeroinput/userdata/SecureClipboardVault.kt:57-59`
- 说明：纯读取操作有副作用（索引修复写盘），且在 `synchronized(lock)` 内做 I/O。`indexStore.write` 失败时 `read()` 抛 `IOException`，调用方可能误判为条目读取失败。仅在主存储与索引已不一致的异常状态下可达。

### 10. EmojiHistoryRepository.recordIfRevision() 写入失败时 lastUsedHighWater 未回退
- 位置：`user-data/src/main/kotlin/dev/zeroinput/userdata/EmojiHistoryRepository.kt:28-29`
- 说明：`store.write` 抛异常后 `lastUsedHighWater` 已被抬高但数据未持久化，后续 `maxOf` 不会降低它，导致单调时间戳"跳空"。仅影响排序时间戳浪费，不影响正确性。

### 11. LanguagePackParser 对 >2^53 的整数字面量经 Double 中转精度丢失
- 位置：`language-pack/src/main/kotlin/dev/zeroinput/languagepack/LanguagePackParser.kt:53-62`
- 说明：`requiredInteger` 走 `Number -> toDouble()`，2^53+1 → 2^53 仍通过 `numeric % 1.0 == 0.0` 校验。后续 `extractVerifiedFiles` 要求 `entry.size == declared.size`，任何真实文件都不可能等于 2^53+1，安装必然失败——属安装期拒绝无运行时危害，但精度口径不一致是纵深防御缺口。`formatVersion: 1.0`（Double）现已被正确接受，历史"安装期拒绝、运行期放行"两套口径问题已修复。

### 12. AiStreamDelivery 达 MAX_OUTPUT_CHARS 静默截断 Delta，与"超限即失败"口径不一致
- 位置：`app/src/main/kotlin/dev/zeroinput/ime/ai/AiStreamDelivery.kt:23`
- 说明：现行唯一 provider（OpenAiCompatibleProvider）走 `AiSseReader`，会在 output 超 MAX 时先抛错，故该截断分支现行不可达。但 delivery 层的截断与"超限即失败"的安全口径不一致，属防御纵深缺口（仅当未来 provider 绕过 AiSseReader 直接 emit 超限 Delta 时可达）。

### 13. abort() 误用 ThreadPoolExecutor.purge()
- 位置：`app/src/main/kotlin/dev/zeroinput/ime/ai/OpenAiCompatibleProvider.kt:65`
- 说明：`purge()` 只清"已取消且仍排队"的 Future，不清有效任务。本请求自己的 task 在 cancel 时已 `future.cancel(true)`，单线程容量 1 队列里该 task 若已在运行则 purge 无效；真正想清队里其他残留应走 `cancelQueued`（存在却未用）。无错误后果（不清有效任务），属误用 API 的死代码。

### 14. KeyboardBackgroundStore 超时/清理边界
- 位置：`app/src/main/kotlin/dev/zeroinput/ime/settings/KeyboardBackgroundStore.kt:37,80-88`
- 说明：(a) `load` 的 generation 校验不覆盖"Activity 已销毁但 Application 级 store 未 close"——15 秒超时边界附近完成解码时，明文位图可能交付给已 release 的预览视图且无人 recycle。(b) `remove` 先删文件后 `deleteKey()`，单文件删除失败被 `catch (Exception) → fallback` 吞掉，留下密钥已销毁、永远无法解密的孤儿密文（重启后 init cleanup 才清）。两者触发都需要特定的时序/占用条件。

### 15. SecureClipboardUnlockActivity 缺 onNewIntent
- 位置：`app/src/main/kotlin/dev/zeroinput/ime/auth/SecureClipboardUnlockActivity.kt`
- 说明：认证完成 `finish()` 后实例进入销毁动画但仍存活，此时另一入口并发 `startActivity` 会把旧实例重新带上前台。类未重写 `onNewIntent`，旧 requestId 保留，新 requestId 永远等不到 Activity，30 秒后才超时回调。功能性挂起（可自愈），需 finish 动画窗口内的并发二次请求。

### 16. ClipboardImportActivity 标签明文走 View 自动 saved-state，存活期超过草稿本身
- 位置：`app/src/main/kotlin/dev/zeroinput/ime/clipboard/ClipboardImportActivity.kt:90-93,150-178`
- 触发：用户在导入页标签输入框敲入文字后，Activity 被系统回收/覆盖安装/进程被杀。带 `android:id` 的 TextInputEditText 文本会被 Android View 层级在 `onSaveInstanceState` 的 super 调用内部自动写入框架持有的 Bundle，`outState.clear()` 拦不住（clear 只清传给 super 后返回的 Bundle，框架保存用的是自己的）。
- 影响：正文（ClipboardImportRequest 的 CharArray）按威胁模型严格清零、销毁即失效，但同等敏感的标签明文被系统序列化、存活期超过草稿本身。泄露面是系统进程持有的 Bundle（非磁盘），需系统级进程回收且标签含敏感内容。
- 修复方向：给 labelInput 显式 `setSaveEnabled(false)`。

### 17. ClipboardImportIntent 对 clipData 过度收紧，AOSP 标准分享器下分享 100% 失败
- 位置：`app/src/main/kotlin/dev/zeroinput/ime/clipboard/ClipboardImportIntent.kt:24-31`
- 触发：用 AOSP 系统分享面板或部分 OEM 分享器分享纯文本。这些分享器把同一段文本同时塞进 `EXTRA_TEXT` 和 `clipData`，但 clipData 的 item.text 经常被规整化（去尾换行、不同 CharSequence 实现），逐字符不等即整体 `return null`，合法分享被静默判为"内容无效"。
- 影响：可用性缺陷（不扩大攻击面，解析仍只认 text/plain）。某些分享器下分享入口 100% 失败。
- 修复方向：clipData 校验失败时回退只用 `EXTRA_TEXT` 作为唯一权威来源，而非整体拒绝。

## 复核后撤销的误报

以下子代理/历史发现经回源码核实后**不成立**，不计入待修：

1. **ClipboardGuardRuntime.refresh 竞态（报"关闭监听后旧会话仍清空剪贴板"，高危）**：不成立。`reconfigure()`（131-133 行）在 `refreshQueued` 之前就 `revision.incrementAndGet()`，任何重配都立即 bump revision。旧 refresh N 跑完后 `queueEvent` 里 `lease(N) != revision(N+1)`，旧会话 `changed()` 不会被调用。lease 与 revision 并非"旧会话合法存活"——lease 在 refresh 内取自当时 revision，reconfigure 立即使其失效。

2. **SecureClipboardVault 并发 clear/remove 复活（中危）**：不成立。`remove()` 全程在 `synchronized(lock)` 内；`clear()` 进锁后先置 `deletionPending = true`。反向交错时 `remove` 后续 `persist` 的 `checkOperationActive`/`checkStorageAvailable` 命中 deletionPending 抛异常；正向交错时 clear 删除 store 是预期全覆盖（被删条目本就该随 clear 消失），非"复活"。

3. **引擎失败降级丢组合串（中危）**：不成立。ime-core 侧 `adoptPreparedEngine`（356 行）和 `reloadEngineIfIdle`（340 行）都有 `state.snapshot.isComposing` 守门，引擎热替换只在 idle 时发生，不会丢用户已输入的组合串。

4. **PersonalExpressionRepository.clear() 不可恢复（中危）**：不成立。`deletionPending = false`（129 行）在成功路径重置，失败路径保持 `true` 是有意 fail-closed（与其他 Repository 一致）。`clear()` 可重试，`deletionGeneration` 递增只让排队旧操作失效，不影响 clear 重试或后续 load 逻辑。

5. **handleSecurePasteConsentResult editorToken 恒 0 形同虚设（历史已知项）**：不再适用。该符号及 `currentEditorToken()` 在当前代码中已不存在。现有 `SecurePasteConsent.bind`（31 行 `editor != source` 拒绝）+ `SecurePasteCoordinator.confirm`（75 行 `consent.source != identity` 拒绝）+ `editorChanged`（49 行）构成 source identity 多重校验兜底。

6. **UserLexiconRepository.persist 代次过期清空快照（报中危）**：不成立。`clear()`（179 行）与 `persist` 代次过期分支（303 行）都 `publishTerms(emptyList())`，语义一致——清除后内存本应为空。所谓"已成功落盘但代次过期"的窗口里磁盘随后也会被 clear 删除，内存清空与磁盘一致，是设计选择而非 bug。

7. **历史已知项 (d) onStartInputView 的 bindPasteConsent 提前作废待粘贴（疑似）**：不成立。认证返回的 restart 序列里 `targetSession == null` 时 `leaveEditor` 不会 close consent；`endInputSession`/`onStartInput` 均 `preservePasteConsent = true`；grant 又在认证 Activity 销毁后才投递，时序闭环。建议从历史清单移除。

8. **删除确认 dialog 显示秘密条目标签（报高危）**：不成立。`SecureClipboardMetadata`（`SecureClipboardVault.kt:319-321`）本身含 `label` 字段，管理页列表在用户认证解锁后本就明文显示这些 label——删除确认再显示一次不构成新增泄露。子代理混淆了两个边界："认证前只能读不含标签的独立索引"（indexStore，`displayName=""`）指的是**键盘侧未认证面板**，而非这个**进入即需认证的管理 Activity**。且该 Activity window（39 行）与 dialog window（203-206 行）均加 FLAG_SECURE，Manifest excludeFromRecents。

9. **UserDictionaryActivity 删除确认 dialog 显示词组正文（报中危）**：不成立。用户词库管理页列表本就显示 value（明文词组是该页的管理对象），删除确认再显示一次不扩大暴露面。Activity（69 行）与 dialog（222 行）均加 FLAG_SECURE，exported=false。属对已认证管理界面的过度解读。

10. **候选条首候选无障碍逐键播报（报中危）**：待确认设计口径，不计入确定 bug。`CandidateStripView.render`（141-147）在 changedInput 时对首候选 `announceForAccessibility`，与 `ZeroInputView.announceCommittedText`"仅提交后播报"的注释口径不一致。但播报留在本机、输入法天然可见该内容，且候选条实时回显首候选是输入法常见无障碍行为。倾向判定为有意设计或低优先级的口径统一问题，而非 bug。

## 补充观察（非 bug，但值得记录）

- **已知项 (a) 的 UX 死路**：`SecurePasteConsent.authorize` 后 `deadline = now() + 30_000L`，而 `confirm` 拿到的 `grant` 自身也是 30s 一次性。若用户在认证返回后第 29 秒点"确认粘贴"，`confirm` 已通过 ready() 检查、`coordinator.cancel()` 已清 pending，但 worker 线程真正执行 `vault.read` 时 `grant.consume()` 可能已超 30s 返回 false，read 抛异常，UI 只 toast"操作失败"且 consent 已被 cancel 不可重试——用户须重新走一遍流程。UX 级死路，非安全漏洞。

## 重点核查后确认无问题

- **两阶段粘贴状态机**：30s 双超时、删除代次、编辑器公开标识、会话令牌绑定、grant 一次性消费、old-IC 防护，全部正确。
- **AES-GCM 加密**：IV 由 provider 随机生成（`setRandomizedEncryptionRequired(true)`），无复用；AAD 分用途隔离；8 个 Keystore 密钥别名相互独立。
- **明文清零覆盖**：EncryptedFileStore、各 Repository、Format decode/encode 路径均有 `fill(0)`（遗漏为低危 #8 exportJson，及中危 #7 的 JSONObject String 副本）。
- **代次失效**：UserLexicon/PersonalExpression/SecureClipboardVault/EmojiHistory 的 clear 均先递增代次再等锁，旧代次操作在清除后失效。
- **deletionPending fail-closed**：所有 Repository 在 clear 失败后保持 fail-closed，可重试。
- **退出路径清理清单**：ZeroInputService 的 selectionReader、engineWarmup、drafts、panels、reconversion 全覆盖；`cancelSecureClipboardRequest()` 第一行即 `selectionReader.cancel()`。
- **onUpdateSelection**：三重 isSessionActive 校验 + `connectionBinding.resolve` 校验，finish/invalidate 顺序正确。
- **引擎边界**：JNI 白名单校验 fail-closed、ExpandingRimeEngine 扩展状态机页耗尽清候选、RimeRuntime 生命周期闭环、GlideCoordinator 代次+身份双校验。
- **语言包 ZIP/JSON/路径防线**：条目数/总大小/单文件大小/路径穿越/校验和/staging 原子替换/深度限制均正确。
- **隐私门控**：`learnedSuggestionsAllowed` 在 start 求值，隐私收紧后经 reset/start 生效；ZeroInputService 无系统剪贴板正文访问。
- **AI 边界**：HTTPS 强制/禁重定向/大小限制、API key 去敏、附件所有权与清零、AppGraph 撤销防复活。

## 验证说明

本轮为静态源码复查 + 关键点交叉核实，未运行全量单测。历史已知项（AI 会话残留、deadline 起点、SSE 行上限、selection 竞态、图片所有权、Emoji 搜索代次、语言包连续操作、剪贴板守护监听器泄漏、明文索引崩溃窗口、EncryptedFileIO 半写、MemoryCrypto 清零）经抽查均已修复且无回退。完整验证仍应按 AGENTS.md §12 执行 `testDebugUnitTest privacyCheck :app:lintDebug` 及 Release 命令。
