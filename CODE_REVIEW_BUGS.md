# ZeroInput 代码复查报告

> 本文档为**滚动更新**的最新审查结论。每次全面复查后重写正文；历史轮次保留在文末折叠区备查，其现状描述、行号与风险判断以最新正文为准。

## 本轮审查（2026-10-08 · 第二轮）

### 范围与方法

- **审查范围**：以工作区相对 HEAD（`12cc3fd`）的全部未提交改动为主（36 个已跟踪文件 + 新增 AI 页面引用/上下文/会话管理/视口自适应，约 1482 插入），并对引擎 JNI、语言包导入、安全/加密/剪贴板、按键热路径做全量复查。
- **审查方式**：6 个并行只读审查子代理（AI 工作台与网络 / 页面引用无障碍 / 输入热路径与并发 / 安全加密剪贴板 / 引擎 JNI 与语言包 / UI 与设置）+ 协调者交叉对抗复核，关键发现逐条回源码复验。
- **本轮为静态只读审查**，未修改任何文件、未运行测试套件。

### 结论概览

| 严重度 | 数量 | 编号 |
| --- | ---: | --- |
| 高危 | 0 | — |
| 中危 | 7 | M1–M7 |
| 低危 / 观察 | 15 | L1–L15 |

上一轮（2026-10-07）针对 AI 页面引用的 H1–H3 修复经独立复验**确认生效、无回归**（详见"修复复验"节）。核心安全防线——密码学、明文清零、一次性认证、两阶段粘贴、系统剪贴板隔离、AI 网络边界、无障碍能力最小化、语言包导入边界——均可读码证实在位（见"核实无问题的关键防线"）。

---

## 中危

### M1. 关闭安全剪贴板开关/清除个人数据均不清理 vault 密文与 Keystore 密钥
- 位置：`user-data/src/main/kotlin/dev/zeroinput/userdata/SecureClipboardVault.kt:141-166`（`clear(grant)` 存在但无生产调用方）；`app/.../settings/MainActivity.kt:217-225`（`setSecureClipboardEnabled(false)` 只置开关）；`app/.../AppGraph.kt:322-340`（`clearPersonalizationData` 不含 secureClipboard）。
- 现状：`SecureClipboardVault.clear(grant)` 需一次性认证且与 `metadata/add/remove` 同级认证门禁；全仓生产代码**无任何调用方**（仅 androidTest 命中）。用户关闭安全剪贴板开关，或执行"清除个人数据"后，`SECURE_CLIPBOARD` 与 `SECURE_CLIPBOARD_INDEX` 两个密文文件及对应两个 Keystore 密钥别名**长期保留**。管理页只有单条 `remove`（`SecureClipboardManagerActivity.kt:233`），无"清空库"入口。
- 影响：功能关闭后内容无法被正规途径访问或删除，违背 AGENTS.md §4"清除用户数据时应删除对应密文及专用 Keystore 密钥"。
- 修复方向：为 vault 提供**豁免认证的 purge 入口**（持锁、升代次、删除两个密文与两个密钥别名），在"关闭开关"与"清除个人数据"路径接入；purge 不应对单次正文读取复用认证授权。

### M2. 语言切换键写 SharedPreferences 后未同步内存镜像，短时触发重复引擎重建
- 位置：`app/.../ZeroInputService.kt:1043-1049`（`KeyboardAction.SwitchLanguage` 分支）。
- 现状：该分支直接 `graph.settings.lastLanguage = it` + `graph.settings.lastLanguagePackKey = null`（`SettingsRepository.kt:84` 走 `apply()` 磁盘提交），但**未调用 `refreshConfiguredSettings()`**。本轮新增的 `@Volatile` 镜像 `configuredLanguage`/`configuredLanguagePackKey`（`:195-196`）因此在窗口期内与磁盘脱节；此后每个按键的 `maybeReloadLanguagePack()`（`:1624-1636`）读到"镜像 ≠ 会话语言"而误判语言变更，无组合时触发 `cancelEngineWarmup(clearInstalled=true)` + `setLanguage()` + `scheduleEngineWarmup(force=true)` 重复换引擎，直至 SP 变更回调刷新镜像。
- 影响：低频路径 2 次 SP 磁盘写 + 镜像脱节窗口内重复引擎重建（卡顿、native 会话反复创建）。
- 修复方向：写入后立即 `refreshConfiguredSettings()` 使镜像同步原子化；或评估将该路径也走镜像单一真相。

### M3. RimeRuntime initialize 与 close 竞态：被取消的初始化残留 native 全局状态
- 位置：`engine-rime/src/main/kotlin/dev/zeroinput/engine/rime/RimeRuntime.kt:79-114`。
- 现状：`initialize()` 在锁外执行 `nativeInitialize`（:84），成功后在 :88-91 无条件下设 `nativeInitialized=true`，仅当后续 :96-100 检测到 generation 失配才 `finalizeNativeLocked()`。若 `close()` 在 :84 成功后、:88 设位前提升 `initializationGeneration`，`initialize` 线程仍在 :88 设位、:90 记旧 generation；`close` 走 `finalizeWhenIdle` 分支，但此后没有任何 `releaseEngine` 触发消费它（`releaseEngine` 仅在引擎 close 时运行）。
- 影响：一次"初始化又被取消"后，librime 全局状态（api、OpenCC converter）保持初始化直到下个成功周期；功能仍 fail-closed 不丢键，但违背 `close()` 的资源释放语义，`initialize` 返回 false 时调用方误以为已清理。
- 修复方向：:88-91 设位前重新校验 `generation == initializationGeneration`，失配则跳过设位直接 finalize。

### M4. 语言包词典加载对非 JSON 文件"跳过式"容错，损坏包降级启用而非失败关闭
- 位置：`language-pack/src/main/kotlin/dev/zeroinput/languagepack/LanguagePackEngine.kt:164-186`（`load`）+ `:238-247`（`parseLines`）。
- 现状：非 json 词典文件超过 `MAX_TEXT_FILE_BYTES`/`remainingBytes` 或文件缺失时 `return@forEach` **静默跳过**（:170、:177），仅 json 走 `return emptyList()`；`parseLines` 用 `runCatching` 吞掉整个 `useLines` 的读取异常（磁盘损坏/UTF-8 损坏），返回"已解析的部分行"。配合 `isAvailable` 仅查 `entries.isNotEmpty()`（:35），一个被部分截断的包会以残缺词典被判定可用并激活。
- 说明：安装期已有逐文件 SHA-256 校验与"zip 实际条目集合 == manifest 声明集合"闸门，**导入的恶意包无法绕过**；本项针对的是**安装后磁盘损坏**场景，此时表现为"词典变少/缺词"而非明确的"包不可用"，与 AGENTS.md §8"无效包失败关闭"精神不一致，且可用性诊断更差。
- 修复方向：任何 manifest 声明文件超限/缺失/读取异常都让整个 `load` 返回 `emptyList()`（包不可用），不要按文件类型区分容错。

### M5. AiStreamEvent.Failed 在会话列表模式下不切回结果页，流式失败对用户不可见
- 位置：`ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/AiWorkbenchPanelView.kt:291-294`。
- 现状：`Failed` 分支 `if (detailMode != DetailMode.CONVERSATIONS) showDetail(RESULT) else setEditing(false)`——当用户处于会话列表（CONVERSATIONS）模式时，错误文本写入此时 `visibility=GONE` 的 `result` 视图，且 `cancel` 被隐藏、流式态视觉上"卡住"，用户看不到任何失败提示。
- 触发：流式 Delta 进行中用户点"会话"按钮切到 CONVERSATIONS，随后流失败。
- 修复方向：`Failed` 时无条件 `showDetail(DetailMode.RESULT)`，或在 CONVERSATIONS 模式下把错误写到 `conversationRows.status` 可见处。

### M6. 语言包解压未显式拒绝符号链接条目，防线依赖隐式三重约束
- 位置：`language-pack/src/main/kotlin/dev/zeroinput/languagepack/LanguagePackInstaller.kt:185-196` + `PackPathPolicy.kt:27-34`。
- 现状：AGENTS.md §8 明列"符号链接"防御，但代码没有单独检查 zip 条目的 unix mode/symlink 位。当前真正能挡住越界写的是隐式三重约束——全新私有 staging 目录（nanoTime 唯一名）+ `actual.toSet() == declared` 集合相等 + `resolveInside` canonical 前缀校验。**在当前实现下 symlink 无法写出 staging 之外，防线成立**；但这是推论依赖而非显式代码。
- 影响：无现行越权；一旦未来放宽"条目集合必须等于 manifest"或复用 staging 目录即开洞。
- 修复方向：补一条显式拒绝——解压前检查条目的外部属性 symlink 位，或对非目录/非普通文件条目一律拒绝。

### M7. RimeInputEngine.readUpdate 未按当前页尺寸截断候选，页尺寸 ≠ 10 时与翻页语义错位
- 位置：`engine-rime/src/main/kotlin/dev/zeroinput/engine/rime/RimeInputEngine.kt:242-255` + `engine-rime/src/main/cpp/zero_rime_jni.cpp:282-287`。
- 现状：JNI 每页硬截 10 条，Kotlin `readUpdate` 把 `update.candidates` 全量放进快照；候选页尺寸由 `RimeConfigurationInstaller.kt:28` 写死 `options.candidatePageSize`（默认 8，可配置）。若实际页尺寸 ≠ 10，快照页候选数与 `hasNextPage`/翻页语义错位（页尺寸 >10 丢候选，<10 首页多出候选）。
- 影响：候选显示/翻页不一致，非崩溃。
- 修复方向：`readUpdate` 里 `.take(options.candidatePageSize)`，或把页尺寸下发给 native。

---

## 低危 / 观察项

- **L1. 添加安全剪贴板条目的明文草稿在认证等待期无超时驻留内存** — `app/.../settings/SecureClipboardManagerActivity.kt:148-157` + `clipboard/PendingClipboardAddition.kt:8-31`。点保存后 `PendingClipboardAddition(CharArray)` 仅在 close/consume 时清零；认证挂起（用户切走/不按）期间明文留堆，直到 `onDestroy` 或下一次操作。导入页有 `DRAFT_TIMEOUT_MILLIS`(120s)，此路径没有等价超时。建议加 30s 过期或 `onStop` 立即 close 要求重输。
- **L2. SecurePasteConsent 预授权窗口 60s，超出口径** — `app/.../clipboard/SecurePasteConsent.kt:15`。预授权仅持条目 id+编辑器公开标识+代次（**不含正文与授权**），authorize 成功后才收紧 30s（:26）。与"授权最多保留 30 秒"口径不一致，但暴露面有限。建议预授权同样 30s 或修订文档。
- **L3. AiWorkbenchController.storage() 失败提示复用** — `app/.../ai/AiWorkbenchController.kt:277-285`。失败分支无条件 `renderHistoryStatus(true,false,true)`，该函数同时服务读历史与改名写路径；改名执行器拒绝/仓库异常时给出"历史加载失败"误导提示。fail-closed 不写坏数据，仅提示位置错误。
- **L4. AiPageReferenceBinding.deliver() 失效路径未清理加载态** — `app/.../ai/page/AiPageReferenceBinding.kt:61-72`。ticket/revision 失效时直接 `return`，未移除 capture 超时 Runnable、未复位 `pending`、未清视图 loading；`CAPTURE_TIMEOUT`(5s) 内会话失效可致面板滞留"加载中"。引用提交仍被 `apply()` 双重校验拦截，无越权。
- **L5. PageReferenceService 锁屏接收器仅 onDestroy 反注册** — `app/.../ai/page/PageReferenceService.kt:38-72`。`onUnbind` 未注销/复位 `receiverRegistered`；服务被系统解绑重建且未走 `onDestroy` 时少一次锁屏撤销。无安全后果（撤销是保守兜底）。
- **L6. AiContextView 确认后引用全文在 View 树无限期驻留** — `ime-ui/.../AiContextView.kt:40-41`。确认后的页面引用（单条可达 4096 字符）非预览模式直接展示无截断，与 H3"缩短 View 树明文驻留"精神不一致。建议确认态默认渲染截断预览。
- **L7. API<33 平台节点缓存残留** — `app/.../ai/page/PageReferenceService.kt:96`。API 33+ 已 `setCacheEnabled(false)`；旧平台副本无法可靠清零，`docs/threat-model.md:781-786` 已明确承认，属记录在案的平台限制，非代码缺陷。
- **L8. scheduleEngineWarmup 在引擎长期 FAILED 且 force=true 路径仍每按键分配 request** — `app/.../ZeroInputService.kt:1432-1459`。新增三个 `matchesWarmup` 短路使引擎 READY 后热路径零分配（净改善）；仅 `unavailableEngineWarmupContext` 不匹配时的 force 重试窗口仍有小分配。可补：`retryEngineWarmupIfIdle` 对 unavailable 匹配也跳过。
- **L9. ZeroInputService.kt:1167 缩进异常** — `configurePairedSymbols` 行前导多两空格，纯格式。
- **L10. handleSettingsChange posted 块读 SP 而非镜像** — `app/.../ZeroInputService.kt:337-339`。结果正确（SP 读即最新值），但与新增镜像防线不一致，双源真相。建议统一走镜像。
- **L11. modelRankingAllowed 每 commit 3 次未镜像 SP 读** — `app/.../ZeroInputService.kt:1762-1766`。SP 内存缓存承担，开销可忽略，仅记录镜像防线未覆盖点。
- **L12. KeyboardPanel.rowHeight() 恒用 portrait，landscape 行高成死参数** — `ime-ui/.../KeyboardPanel.kt:438`。`heightPreset.rowHeight(false)` 使 STANDARD/COMFORTABLE 的 landscape 值（48/52）不再生效，非 compact 横屏键盘比上一版更高。若为刻意统一，建议删除 `KeyboardHeight` 的 landscape 死参数；否则按 viewport 选 portrait/landscape。
- **L13. docked 模式底部 inset 行为变化待真机验证** — `ime-ui/.../KeyboardLayoutHost.kt:172-173` + `ZeroInputView`。非 floating/非展开时 `setMeasuredDimension` 不再叠加 `safeInsets.bottom`，且 `ZeroInputView` 因 `externalInsets=true` 不自行应用底部 inset；手势导航 + docked 下键盘底排可能贴导航栏。需真机验证，非确定 bug。
- **L14. RimeConfigurationInstaller.removeUnused 用 check(delete) 清理** — `engine-rime/.../RimeConfigurationInstaller.kt:50-58`。单个残留文件删除失败即中断整个 schema 切换（误判引擎失败）。清理是尽力而为操作，建议 `runCatching` 收集失败不阻断。
- **L15. 语言包 enabled.json 损坏时默认启用偏宽松** — `language-pack/.../LanguagePackInstaller.kt:267`。`enabled[key] ?: !enabledStateInvalid` 在状态文件损坏时新包默认 `enabled=true`。词典本身已过完整性校验，建议改默认不启用（`?: false`）。

---

## 修复复验（上一轮 H1–H3）

- **H1（事件撤销参照物混淆）— 已修复且更保守**：`PageReferenceService.kt:45-50,81-84` 事件回调只查 `eventType`，`TYPE_WINDOW_STATE_CHANGED` 与 `TYPE_WINDOWS_CHANGED` 均**无条件 `revoke()`**，同包名跳转/窗口 ID 复用漏不掉；回调不读 `event.text`/`source`/窗口根。有 `PageReferenceEventPolicyTest` 负向测试。
- **H2（stop() 残留上下文）— 已修复**：`ZeroInputService.kt:993` `cancelAiRequest()` = `workbench.cancelRequest()`（`AiWorkbenchController.kt:60-66` 重置上下文）+ `aiPages.invalidate()`；草稿编辑路径（:838,:1072）只 `stop()` 保留选择，与 ADR 语义分离正确。
- **H3（明文暴露面）— 已修复**：审查态 30s（`AiPageReferenceBinding.kt:71`）、复选框仅编号+长度+160 字符代理对安全预览（`AiPageSelectionView.kt:31-34`）、完整快照只在 binding。

## 核实无问题的关键防线

- **密码学**：AES-256-GCM、128 位 tag、Keystore 生成、`setRandomizedEncryptionRequired(true)`、envelope 版本头+IV 校验、解密 fail-closed、无硬编码密钥/固定 IV；密钥别名 9 项各自隔离；密文落 `noBackupFilesDir/encrypted`，写后缓冲 `fill(0)`。
- **认证与两阶段粘贴**：`AuthenticationGrant` 一次性 consume+≤30s+不可序列化；vault 每次正文操作均 `grant.consume()` 门禁；全程不持正文/旧 `InputConnection`，切编辑器/设置/超时使意图失效，后台读前与提交前双重校验；认证前 `summaries()` 只含 id+时间戳、displayName 恒空。
- **系统剪贴板隔离**：唯一生产适配器只读时间戳元信息、清理只写空文本字面量、不读正文、锁屏前置。
- **AI 网络边界**：强制 HTTPS、禁 userInfo/query/fragment（`AiEndpoint.parse` 对所有输入）、`instanceFollowRedirects=false`、超时上限、凭据仅 Authorization 头不进日志/异常、2MB 请求上限、取消真停连接（`future.cancel(true)`+`disconnect`+附件字节清零）、附件媒体能力校验。
- **AI 代次与引用注入收敛**：generation+dataGeneration+contextCurrent 三重乱序/过期防护；`AiReference` 控制字符/长度/条数/总预算校验；references 打包为 `quoted_references` USER 数据不可提升为 SYSTEM；SSE/JSON 输出上限与 `finish_reason` 严格校验；会话仓库只写 role+content。
- **无障碍能力最小化**：xml 仅 `flagRetrieveInteractiveWindows`+`canRetrieveWindowContent`（v31 `isAccessibilityTool=false`），无手势/截图/滚动/剪贴板/按键能力；采集侧包名/锁屏/唯一聚焦/节点元信息（不可见/可编辑/密码/API34 sensitive）先于文本/遮挡排除，512 节点/32 深/128 块/32768 字符上限，节点全部 recycle；明文不落盘、toString 脱敏。
- **语言包导入边界**：128MB 归档上限、256MB 解压闸门、逐文件 SHA-256、staging→原子替换回滚、反穿越/绝对路径/重复名/可执行扩展名白名单、JSON 深度 16 预扫描、zh/en 白名单、错误消息不含正文。
- **引擎 JNI**：全入口互斥 + ContextLease/CommitLease RAII + `select_schema` 失败即销毁（防 librime 用户词典学习）+ schema/路径白名单 + `set_input` 限长白名单 + 候选指针判空。
- **按键热路径**：无磁盘 I/O/Keystore/native 初始化/阻塞；设置已镜像化；新增三个 `matchesWarmup` 短路使引擎 READY 后零分配；`adoptPreparedComposition` 的拼音 handoff 仅允许中文/无包/fallback/纯小写拼音 1..128 字符/无选段，restore 后回等校验，失败 `closeSafely`。

## 对抗复核后不计入的疑似项（误报澄清）

1. **"AiEndpoint 显式 /chat/completions 形态可携带 query/fragment 外发"** — 不成立。`AiEndpoint.parse:13` 对**所有**输入拒绝 query/fragment，且 chat/models URI 从干净的 `origin+path` 重建，用户自填端点带 `?key=` 会被 parse 拒绝。
2. **"语言包符号链接可写出 staging 之外"** — 不成立为现行越权。全新私有 staging + 条目集合==manifest + canonical 前缀三重约束下 symlink 无法越界；M6 仅要求把该防线从隐式推论改为显式代码。

## 验证说明与未验证项

- 本轮为**静态只读审查**，未修改任何文件，**未运行测试套件**；`adb` 无连接设备，未做真机/仪器化验收。
- **建议补测**：M2 语言切换后镜像同步回归；M3 initialize/close 竞态；M5 会话列表模式流式失败可见性。L12/L13 需真机横屏 + docked/手势导航验证。
- 完整验证应按 AGENTS.md §12 执行 `./gradlew.bat testDebugUnitTest privacyCheck :app:lintDebug :app:assembleDebug -PrequireRime=true --no-parallel` 及 androidTest。

## 建议处理优先级

1. **M1**（数据清除边界，密文/密钥残留）→ 2. **M2**（热路径镜像脱节）→ 3. **M5**（流式失败不可见）→ 4. **M4**（语言包降级启用）→ 5. **M3**（native 初始化竞态）→ 6. **M6**（导入防线显式化）→ 7. **M7**（候选页尺寸）→ L*（打磨，L1/L12/L13 优先）。

---

<details>
<summary><b>历史记录（点击展开）：2026-10-08 第一轮修复核对 + 2026-10-07 原始审查</b></summary>

# 历史：代码复查与修复核对报告（2026-10-08 第一轮）

## 修复核对（2026-10-08）

用户已确认修复方案。本次逐项核对后完成 12 项修复或边界收紧（H1–H3、M1–M5、L2–L5）；L1 在当前代码中未证实，L6 保留为观察项。下方 2026-10-07 的审查正文保留为历史记录，其现状、行号、风险判断和建议不能代替本节的当前结论。

| 编号 | 当前结论 | 实现与验证 |
| --- | --- | --- |
| H1 | 已收紧 | 已绑定来源时，窗口状态及窗口集合变化统一撤销旧引用，不以可复用的包名/窗口 ID 判断页面未变。新增事件策略负向测试；真实页面导航仍需设备验收。 |
| H2 | 已修复 | 显式取消关闭输出、清除所选历史和全部引用，并撤销页面绑定；普通草稿编辑的 `stop()` 保留选择。回归测试覆盖取消、编辑、再次提交及旧回调。 |
| H3 | 已收紧 | 未确认快照有效期缩为 30 秒；完整文本仅由 binding 持有，复选框只显示编号、长度和最多 160 个 UTF-16 单元的预览，截断不拆代理对。新增平台回归测试已编译，尚未执行。 |
| M1 | 已修复 | 语言/语言包设置加入内存镜像，移除每次按键尾部的无条件重复检查；预热请求先比较再分配。组合期间推迟的设置仍在组合结束后立即应用。引擎及核心测试检查通过，未进行设备延迟测量。 |
| M2 | 已修复 | 拒绝包含孤立或错序代理字符的整个导入，保留合法代理对。回归测试覆盖尾部高代理、孤立低代理及合法补充字符的分块边界。 |
| M3 | 已修复 | 标题导入失败即退出改名并恢复原问题草稿；用独立回调端口补充失败路径测试。 |
| M4 | 已修复 | 空白、过长或含控制字符的标题自动取消改名，先恢复问题再提示拒绝，避免提交键反复进入失败改名。 |
| M5 | 已修复 | 最近历史跳过 SYSTEM 消息，只对可选历史计数及扣除预算，维持最近合法连续后缀。补充角色过滤和条数/字符预算测试。 |
| L1 | 当前未证实 | 开启分支在解释前已 `render()` 复位实际开关；`onResume()` 再次读取设置。没有新增生命周期状态或改动此页。 |
| L2 | 已修复 | 与 H1 一并处理：事件回调只检查事件类型并撤销，不同步枚举窗口，不读取节点或正文。显式捕获的窗口校验保持不变。 |
| L3 | 已修复 | 改名成功后静默刷新会话列表，普通加载仍显示 loading。测试同时验证这两条路径。 |
| L4 | 已修复 | 头部边距直接更新两个 View，移除刷新时的两元素 `listOf` 分配。 |
| L5 | 已修复 | 越权 API 扫描覆盖名称词边界，增加四种 callable reference 负向样本；`privacyCheck` 通过，原有网络/剪贴板约束未放宽。 |
| L6 | 保留观察 | 非逐键热路径，没有测量依据支持新增外观缓存，本次不修改。 |

核对原报告时发现三处需要澄清的描述：

- H1 原文将"与本应用包名比较"和"同源包名因此不撤销"混用，不能由该条件推出后者；且包名与窗口 ID 都不足以证明仍为原页。因此采用窗口事件保守撤销，而不是只替换比较对象。源页面打开弹窗等窗口变化也会撤销，用户需重新引用，这是安全优先的明确取舍。
- H3 采集器已有 **32768 个字符的总预算**，不是所有 128 段都能各占 4096 字符。修复缩短审查态生命周期并减少 View 文本副本，不声称 JVM 不可变字符串可以可靠清零。
- M2 在 `end == text.length` 时不存在下一段，原文"拆散合法代理对"的推导不成立；实际缺口是接受本身已损坏的 UTF-16 输入，现已整体拒绝。

架构、ADR 0019、威胁模型、`SECURITY.md` 与计划文档已同步；导入引用的不可变 JVM 字符串残余风险也已补充，未更改持久化格式、增加权限或放宽数据边界。

### 第一轮验证

从仓库根目录依次执行，均为 **BUILD SUCCESSFUL**：

```powershell
./gradlew.bat testDebugUnitTest :ai-api:test privacyCheck :app:lintDebug `
  :app:assembleDebug :app:assembleDebugAndroidTest :app:processReleaseMainManifest `
  -PrequireRime=true --no-parallel
./gradlew.bat :app:testDebugUnitTest privacyCheck :app:lintDebug `
  :app:assembleDebug :app:assembleDebugAndroidTest -PrequireRime=true --no-parallel
./gradlew.bat :engine-api:test :engine-english:test :engine-dictionary:test --no-parallel
```

第一轮完整检查后再次验证了事件撤销、Unicode 预览和组合结束后的延迟设置应用。12 个任务对应 XML 共 **547 项测试，失败/错误/跳过均为 0**（其中 app 205 项）；未变更的任务使用 Gradle up-to-date/cache 结果，不表示全部测试重新执行。Lint 为 `No issues found.`；Debug/Release 合并清单均禁备份，Release 未开启调试。三个 Debug APK 分别包含 arm64-v8a、armeabi-v7a、x86_64 的 Rime 库。

**未验收项（第一轮）：** `adb devices -l` 无连接设备，未运行 instrumentation、真实无障碍页面跳转、主题/旋转/大字体及组合期间设置切换验收；仅完成平台测试 APK 编译。未执行 Release APK 构建。

---

# 历史：原始审查记录（2026-10-07）

> 原始报告当时只保留尚存问题。上一轮报告（修复核对版）的全部"已修复 8 条"经回源码逐条复验确认已修复生效、无回归（详见文末"复验结论"）。
> 原始审查范围：自上次复查后的新增改动——无障碍"页面引用"功能（ADR 0019，工作树未提交，约 29 文件）+ AI 上下文选择/会话管理/重命名 + 全仓热路径性能复查。
> 原始审查方式：4 个并行审查子代理（无障碍边界 / AI 控制器与网络提供者 / 生命周期并发与设置 / 回归复验与性能）+ 协调者交叉对抗复核。该次为静态只读核查，未修改文件、未运行测试套件。

## 原始问题汇总

| 严重度 | 数量 | 编号 |
| --- | ---: | --- |
| 高危 | 3 | H1、H2、H3 |
| 中危 | 5 | M1、M2、M3、M4、M5 |
| 低危 / 观察 | 6 | L1、L2、L3、L4、L5、L6 |

### 高危（均已修复，见上"修复核对"）

- **H1. 页面引用事件回调撤销判断用错参照物**（`PageReferenceService.kt:48-49`）：把自身包名当源包名比较。已修复。
- **H2. `stop()` 不重置上下文代次**（`AiWorkbenchController.kt:49-57`）：中途取消后旧引用残留注入下一次请求。已修复。
- **H3. 页面引用快照明文驻留 60 秒**（`AiPageReferenceBinding.kt:21,67-71`）：暴露面过大。已收紧至 30s+预览截断。

### 中危（均已修复，见上"修复核对"）

- **M1.** 每按键重复 `maybeReloadLanguagePack` + 未镜像 SP 读取。已修复（加入 `@Volatile` 镜像、移除尾部重复调用）。
- **M2.** `importedReferences` 高代理结尾拆散代理对。已修复（整体拒绝损坏 UTF-16）。
- **M3.** `AiConversationNameEditor.start` 未检查 `appendImported` 返回值。已修复。
- **M4.** 重命名标题校验失败后提交键语义死锁。已修复（校验失败自动 cancel）。
- **M5.** `AiContextSelection.recent()` 遇 SYSTEM 截断而非跳过。已修复（跳过 SYSTEM 并对可选历史计数）。

### 低危（L2–L5 已修复，L1 未证实，L6 保留观察）

- L1 设置页解释对话框 onStop dismiss 后不重建 — 当前未证实。
- L2 TYPE_WINDOWS_CHANGED 主线程同步枚举 windows — 已修复。
- L3 renameConversation 成功后闪 loading 态 — 已修复。
- L4 ZeroInputView.refreshHeader 每按键分配 2 元素 List — 已修复。
- L5 privacyCheck 越权 API 正则仅匹配直接调用 — 已修复。
- L6 KeyboardPanel.bind 每次 render 对 Enter/Shift spec.copy — 保留观察。

### 原始审查的"核实无问题"防线与"不计入"疑似项

原始报告核实了无障碍越权边界、AI 网络安全、会话/重命名持久化、热路径架构边界均到位；并将 13 项疑似问题（引用逃逸成 SYSTEM、来源标识外泄、明文落盘、副作用有害、重入窗口、字节低估、隐私收紧残留、跨会话复活、开关视觉不一致、dropped 数学、deliver 竞态、节点缓存副本、导入引用驻留堆）逐项澄清为误报或文档一致性项。详见第一轮报告对应章节（结论已在滚动更新中被本轮复验取代）。

</details>
