# ZeroInput 性能与体验核查报告

本报告基于对按键主链路、引擎层、UI 渲染层、异步并发层与内存/线程模型的静态代码核查。仓库中已有的 `performance-analysis.md`(2026-09-21) 所列 P1/P2 项经逐一对照当前代码,绝大部分已落地(设置镜像、英文引擎手写循环、主题色缓存、Enter 局部重绑、secondary 懒创建、`updatePrivacy` 短路、`CandidateItemView` 双字段比较、`PersonalCandidatePaging` 空页短路、`RimeInputEngine.readUpdate` 直接传 `update.comments` 等)。**本报告只列当前代码中仍然存在或新发现的问题**,按严重度分级,不含任何代码修改。

所有结论均为静态阅读推断;落地前建议按 `docs/architecture.md` 的 public-fixture 插桩复测 "dispatch → editor update → next frame" 三段耗时,并用 allocation tracker 验证每按键分配量的实际降幅。

---

## 一、P1 — 每次按键都会触发的冗余开销

### 1. `maybeReloadLanguagePack()` 仍然在每次按键被调用两次
`ZeroInputService.kt:938` 与 `:968`(`handleControllerCommand` 同样在 `:988`/`:990`)。稳态下每次调用会走 `graph.settings.lastLanguage` / `lastLanguagePackKey`(2 次 SharedPreferences 读取)+ `scheduleEngineWarmup` → `reconcileChineseOptions()` + `session.warmupRequest(...)`(分配 `EngineWarmupRequest`,内含 `SessionPrivacy` 引用比较)。旧报告已指出此问题,当前代码未改。

**改法**:移除按键尾部那次调用(`:968`),或给 `maybeReloadLanguagePack` 加"自上次检查后设置未变化"的脏标记短路。语言/语言包只能被设置页、子类型回调或键盘语言切换改变,这些路径都有自己的监听与主动 `scheduleEngineWarmup`,按键尾部复查没有收益。

### 2. `reconcileChineseOptions()` 每次按键无条件向 View 推设置
`ZeroInputService.kt:1567-1583`:`scheduleEngineWarmup` 每次按键(经 `maybeReloadLanguagePack`)都会调 `reconcileChineseOptions()`,其中第一行就是 `inputView?.renderChineseOptions(configured)`——即使 `configured == sessionChineseOptions` 直接 return,View 侧已被调用。`ZeroInputView.renderChineseOptions`(`ZeroInputView.kt:443-456`)每次执行 4 次 `context.getString` + `ViewCompat.setStateDescription` + `resolveColor`(`obtainStyledAttributes`),这些 setter 大多幂等但不免费。旧报告只点了"重建设置快照"(已修复),但 View 侧的无条件推送仍在。

**改法**:`reconcileChineseOptions` 只在 `configured != sessionChineseOptions`(或显式请求)时才调用 `renderChineseOptions`;View 侧也可加"与上次值相同则跳过"的守卫。

### 3. `renderEngineStatus()` 每次状态变化都分配 warmup 请求并做三次 View 调用
`ZeroInputService.kt:1585-1602`,在 `onStateChanged` 回调里每按键触发:

- `:1594` `installedEngineWarmupContext == session.warmupRequest(sessionChineseOptions, nativeRetryRequested)` —— 仅为一次相等性比较就构造整个 `EngineWarmupRequest` 数据类。
- `:1598-1600` 无条件 `renderChineseOptions` / `renderActiveLayout` / `renderActiveDoublePinyin`,View 端 `updateKeyboardLayout()`(`ZeroInputView.kt:468-480`)每次按键重算九键/双拼条件并多次 `context.getString`。

**改法**:把 `warmupRequest` 的比较换成比较其组成字段(`sessionToken`/`language`/`languagePackKey`/`privacy` 等已持有的值),避免分配;`renderActiveLayout`/`renderActiveDoublePinyin` 只在 `sessionChineseOptions` 实际变化时推送(`ZeroInputView` 内 `chineseLayout`/`doublePinyinScheme` 已有字段,可在 setter 内做相等短路——目前 `renderActiveLayout` 无短路直接赋字段并调 `updateKeyboardLayout`)。

### 4. `ZeroInputView.refreshHeader()` 每次按键重建临时 List
`ZeroInputView.kt:896`:`for (view in listOf(toolbar, candidateStrip))` —— `refreshHeader` 在每次 `renderSession`(即每按键)执行,分配一个含 2 元素的 `ArrayList` 及其迭代器。改为直接对两个 View 各写两行,或提为私有常量数组。

### 5. `CandidateStripView.render()` 每按键分配去重 Set
`CandidateStripView.kt:112`:`previousSnapshot?.candidates.orEmpty().map { it.text }.toSet()` 每按键分配 List + HashSet(8~30 个候选),但它只在 `requestedPage == PageDirection.NEXT`(翻页动画)时才被使用。

**改法**:把 `oldTexts` 的计算移到 `if (requestedPage == PageDirection.NEXT)` 分支内部。普通按键路径完全不需要它。

### 6. `CandidateItemView.bind()` 每可见候选每按键两次字符串格式化
`CandidateItemView.kt:57-60`:

- `:59` `context.getString(R.string.candidate_description, candidate.text)` —— 每次 bind 都做一次带格式参数的资源字符串拼接(无障碍描述),即使候选未变。
- `:56-57` RELATED_READING 时 `context.getString(R.string.related_candidate, ...)` 同理。

可见候选 8~30 个,每按键 16~60 次 `getString`+格式化。**改法**:仅当 `identity`/`identityText` 变化(已有该判断用于 `bindingRevision++`)时才重设 `text` 与 `contentDescription`;`TooltipCompat.setTooltipText` 同理按 comment 变化短路。

### 7. `PersonalCandidatePaging.publish()` 对个人页命中路径仍有每按键的 `suggestionPage` 同步调用
`PersonalCandidatePaging.kt:51-54`:`publish` 在每次按键都会调用 `store.suggestionPage(...)`。对 `QueuedPersonalizationStore` 这是缓存命中(便宜),但每次命中仍走 `synchronized(stateLock)` + `cached.copy(items = take(limit))`(`QueuedPersonalizationStore.kt:76-77`,分配新 Page + 子列表)。更重要的是:**缓存未命中时 `publish` 返回 `ready=false` 的空页,`PersonalCandidatePaging` 会以"上一次可见页"兜底,但每次按键仍重复投递查询键检查**。属设计内行为,但 `publish` 的返回值路径(`:80-88`)在个人页非空时每按键执行 `pages.entries.flatMap { ... }` + `distinctBy(Candidate::text)`(分配拼接 List + HashSet)。空页短路已做,非空页(用户有个人词组时)的拼接去重仍在热路径。

**改法**:`offset == 0 && pages.size == 1 && 仅首页` 时,直接 `rows(first) + native.candidates` 后再去重,避免 `flatMap` 的中间 List;或对个人页结果按 `loaded.revision` 缓存拼接结果,仅在 revision/原生快照变化时重算。

### 8. `InputSessionController.publish()` 的 `withKaomoji` 每按键分配 HashSet
`InputSessionController.kt:691-701`:满足前置条件后(中文、组合中、rime 引擎),`:696` `snapshot.candidates.mapTo(HashSet()) { it.text }` 每按键分配一个 HashSet(8~38 项),随后 `kaomojiCandidates.suggestions(...)` 还有自己的查询开销。kaomoji 候选最多 4 个、仅对 2..32 长的纯小写输入生效,但 HashSet 分配对所有满足长度条件的中文组合键都发生。

**改法**:`extra` 最多 4 项,改为先取 `suggestions(...).take(4)`,再用小型 `ArrayList.contains`(≤4 次字符串比较 × 候选数)替代 HashSet;或给 `KaomojiCandidateIndex` 加"输入未变则复用上次结果"的缓存。

### 9. `rebuildRoutes()` 每按键为每个候选做 `startsWith` + `removePrefix` 分配
`InputSessionController.kt:765-778`:每个候选走 `id.startsWith("kaomoji:")` → `removePrefix(...)`(分配新串)/ `startsWith("personal:")` → `removePrefix(...)`,否则 `candidateWindow.route(candidate.id)`(HashMap 查询)。每按键 8~38 个候选,产生若干小字符串分配与多次 `startsWith`。

**改法**:`CandidateRoute` 分支按 `Candidate.kind`/来源字段直接判定(引擎候选与个人候选在 `PersonalCandidatePaging`/`withKaomoji` 里已知来源,可把路由信息随 Candidate 一起带上,或给 `Candidate` 增加来源枚举),避免按字符串前缀回推身份。

### 10. `ZeroInputService.kt:437` 的 `setOf` 在 `onStartInputView` 每次调用分配
低频(每次键盘弹出一次),但写法与已修复的热路径问题同类:`graph.clipboardGuard.state.status in setOf(UNAVAILABLE, BLOCKED, FAILED)`。改为 `when` 或三个 `==` 比较即可,顺手清理。

---

## 二、P2 — 后台/低频路径与结构性问题

### 11. `UserLexiconRepository.publishTerms()` 全量重建前缀索引,写入放大明显
`UserLexiconRepository.kt:319-336`:每次 `learn`/`recordUse`(即每次用户选词上屏,后台线程)都会 `publishTerms`,对全部词条(上限 20,000)的每个 shortcut 的**每个前缀长度**(1..len)重建 `HashMap<IndexKey, ArrayList>` 并对每个桶排序。一条 shortcut 长 20 的词组就产生 20 个桶引用;2 万词条、平均长 8 的场景是 16 万次 `getOrPut`+`add` + 数千次排序。这在后台单线程上执行,虽不在主线程,但 `delegateLock` 串行化意味着它会挡住后续的查询任务(`suggestionPage` 也在同一把锁里),直接拉长"学习后第一次查询"的延迟。

**改法**:索引改为增量维护(`learn` 只影响一条 term,可只更新其 shortcut 前缀桶;频率变化只需对受影响桶做局部重排,或干脆查询时用堆取 top-N 而不保全序);或把"频率排序"从索引中剥离,索引只存前缀→词条集合,排序在查询时对命中集(通常很小)进行。

### 12. `UserLexiconRepository.frequenciesFor()` 每次联想重排都全表扫描
`UserLexiconRepository.kt:79-96`:对 `wanted`(≤32 词)在全部 `loadTerms()` 上线性扫描匹配 `term.value in wanted`。词库大时(2 万条)每次提交后的联想频率查询都是 O(N)。`QueuedPersonalizationStore` 有 256 条频率缓存兜底,但缓存未命中(新词)时就是一次全扫。

**改法**:`publishTerms` 时同时维护 `Map<(language, value), Int>`(值→累计频率)索引,查询降为 O(wanted)。

### 13. Service 常驻线程数仍然偏多
当前 `ZeroInputService` 级执行器:`secureClipboardExecutor`(`ZeroInputService.kt:90`)、`localDataExecutor`(`:98`)、`selectionReader` 内部线程、`HandwritingCoordinator.worker`(`HandwritingCoordinator.kt:61`)、`GlideCoordinator.worker`(`GlideCoordinator.kt:24`)、`ModelRankingCoordinator` 的懒 `zeroinput-model`,加 AppGraph 共享的 `engine-worker`、`personalization`、`ai-worker`/`ai-cancel`/`ai-document`/`ai-storage`、`clipboard-guard` —— 常驻 8~12 个。旧报告建议合并 `local-data`/`secure-clipboard`/`copy-selection` 三个 Service 级执行器,当前仍未合并(`copy-selection` 已并入 `selectionReader`,但前两个仍各自单线程)。每个线程栈 512KB~1MB,合计数 MB 常驻。

**改法**:`localDataExecutor` 与 `secureClipboardExecutor` 合并为一个容量 2 的共享串行执行器(两者任务都短脉冲且已有代次/令牌失效保护,合并不改取消语义);AI 四个执行器(`aiExecutor`/`aiCancellationExecutor`/`aiDocumentExecutor`/`aiPersistenceExecutor`)在 AI 总开关关闭时纯闲置,可全部改懒创建(当前 `AppGraph` 构造即建线程)。

### 14. `EmojiPanelView` 每次 `renderPersonal` 都触发全量 `filterEntries`
`ZeroInputService.renderLocalPanels` → `view.renderExpressions` → `EmojiPanelView.renderPersonal`(`EmojiPanelView.kt:117-120`)→ `refresh()` → `filterEntries()`。`renderLocalPanels` 在 `onStartInput`、`onStartInputView`、`onFinishInput`、设置变化、个人数据回调等多处被调用,每次都会 `adapter.submit(emptyList(), ...)` 先清空再重新 resolve(`ExpressionBrowserState.request().resolve()` 对 RECENT 分类做 `recent.mapNotNull { custom[it] ?: catalog.find(it) }`,对搜索态做全目录过滤)。面板关闭时这些计算全部浪费(`EmojiPanelView` 不可见仍执行 resolve 与 adapter 提交)。

**改法**:`filterEntries` 在 `!isShown` 或面板不可见时只记录脏标记,`showMode(EMOJI)` 时再真正 resolve;或 `ZeroInputService` 在面板未打开时跳过 `renderExpressions` 的数据投递(目前缓存已就绪,只是白白刷新 UI)。

### 15. `EmojiCatalogSnapshot.find` 的 unqualified 回退每次未命中分配 copy
`EmojiCatalog.kt:62`:`byValue[value] ?: unqualified[value]?.copy(value = value)` —— 每次查找带 FE0F 变体的未限定形式都分配一个新 `EmojiEntry`。`ExpressionBrowserState` 的 RECENT 解析与 `recent(values)` 都会逐条走这里。低频但属于可避免分配;可在 snapshot 构建期预生成 unqualified 副本并存 `EmojiEntry`。

### 16. `ExpandedCandidatesView.render()` 每按键构造 DiffUtil.Callback 与旧 ID 集合
`ExpandedCandidatesView.kt:80-85`:`old.mapTo(HashSet()) { it.id }`(最多 6 页 × 页大小)+ `DiffUtil.calculateDiff(object : DiffUtil.Callback() {...})`(匿名类分配)。仅在展开候选面板打开且按键时触发,频率低,但旧报告已点、当前未改。Callback 可提为命名复用类;`oldIds` 集合仅在需要计算 `newPage`(explicit 翻页)时使用,可移到分支内。

### 17. `EngineWarmupCoordinator.request()` 每次请求取消并重建 Future
`EngineWarmupCoordinator.kt:60-72`:`currentTask?.cancel(true)` + `purgeCancelledTasks()` + 新 `submit`。稳态下因 `scheduleEngineWarmup` 的 `installedEngineWarmupContext == request` 早退(`ZeroInputService.kt:1349`)不会走到这里;但语言/隐私/配置漂移、会话切换时,每次都会打断正在进行的 native 会话创建。`cancel(true)` 对正在执行的 librime 初始化只是置中断标记,librime 不响应中断,实际效果是让 worker 跑完后丢弃结果再重来 —— 首次输入期间连续两次会话切换(常见:焦点在两个输入框间快速切换)可能让 native 引擎就绪时间翻倍。**这更多是延迟体验问题**:可考虑请求合并(若新请求与在途请求等价则挂在在途任务上),而非无条件取消重排。

### 18. `KeyboardPanel.glideKeys()` 在每次 ACTION_DOWN 重建 Rect 列表
`KeyboardPanel.kt:144-158`:`begin` 时 `keys.mapNotNull { ... Rect() ... }` 对 ~30 个按键各分配 `Rect` + `GlideKey`。仅滑行开启且按下时触发(每个触摸序列一次),频率可接受;但 geometry 只在 `render()`/`rebuild()` 后才会变化,可在 `render` 时预计算缓存,DOWN 时直接复用。

### 19. `GlideTouchTracker` 每次 MOVE 触发 `host.invalidate()` 全键盘重绘
`GlideTouchTracker.kt:48` 与 move 路径(`:88+`,trail 追加时同样 invalidate host)。`KeyboardPanel.dispatchDraw` 里 `glide.draw(canvas)` 在每次 MOVE 全量重画整条 Path(点数随滑动增长,O(n) 每帧)。中低端机长句滑行时这是稳定的掉帧来源。

**改法**:用 `invalidate(dirtyRect)`(上一段尾的包围盒 + 线宽)替代整视图 invalidate;Path 分段缓存(每段成独立 Path 或记录 lastBounds 只重绘增量区)。

### 20. `WordAssociationIndex.suggest` 对中文做逐后缀 HashMap 探测
`WordAssociationIndex.kt:17-27`:中文上下文每次提交后遍历所有起始位置做 `index[value.substring(start)]`(每次 substring 分配)。上下文 ≤32 字符,最多 31 次分配+查找;发生在主线程(`InputSessionController.commitPredictableText` → `associations.committed`)。量小但属热路径分配,可用 `CharSequence` 键或前缀树避免 substring。

### 21. `ZeroInputView.updatePanelLayout()` 每次布局都新建 LayoutParams
`ZeroInputView.kt:959-985`:为 7 个子面板每次分配新 `LayoutParams` 对象并赋值(触发 requestLayout)。`renderSession` → `showMode`/`updatePanelLayout` 链路在面板切换、测量变化时执行;正常按键不触发(`updatePanelLayout` 不在 `renderSession` 直接路径),但 `onMeasure` 里 `bodyLimit` 变化时会。建议仅在与当前值不同才替换 LayoutParams,减少多余 layout pass。

---

## 三、体验(UX)问题

### 22. 首次进会话的"降级引擎→native 引擎"切换窗口会拉低首句出词质量
设计上首帧用 `createImmediateEngine`(fallback),native 就绪后 `adoptPreparedEngine` 仅在 `!isComposing` 时接管,组合中会设 `engineWarmupRetry = true` 等提交后再换装(`ZeroInputService.kt:1384-1392`)。**体验后果**:用户打开键盘立刻快速输入前几个字时,整段第一次组合都由 fallback 引擎出词(质量低于 Rime),提交后才切到 native;慢设备上这个窗口可能持续整句。建议:fallback 阶段在候选条给极轻的"词库准备中"提示已做(`EnginePreparationView`),但可以考虑 fallback 引擎与 native 共用同一套拼音切分缓存,或在组合中"热替换"引擎并重放 rawInput(`restoreComposition` 已存在,技术上可行,需评估正确性风险)。

### 23. `onStartInput` 里 `graph.settings.lastLanguage` 写回触发完整设置监听链
`ZeroInputService.kt:368-369`:子类型语言与持久化不一致时直接写 SharedPreferences,触发 `handleSettingsChange` 全量回调(`invalidatePendingPersonalization` + post 一整段 reconcile)。这是正确性要求(保持系统选择同步),但 `apply()` 的 listener 回调与 `onStartInput` 主链路在同帧串行执行,新会话首个按键可能排在 reconcile 之后。可把"由 IME 自身写回的语言"标记为内部回写,`handleSettingsChange` 对该回写跳过重复 `scheduleEngineWarmup`。

### 24. 候选条首候选无障碍朗读在快速输入时逐键打断
`CandidateStripView.kt:141-147`:`changedInput && candidates.isNotEmpty()` 时 `announceForAccessibility(首候选文本)`。快速连续输入时 TalkBack 用户会被每键一次的首候选播报淹没(announce 会打断排队)。主流输入法通常只在候选"稳定"(如停顿 300ms)或用户浏览候选时才播报。建议加去抖或仅在翻页/显式浏览时播报。

### 25. `EmojiPanelView` 搜索态每个字符都走 worker round-trip 且先清空列表
`EmojiPanelView.kt:233-266`:`filterEntries` 先 `adapter.submit(emptyList(), ...)` 清空并显示 emptyLabel,再异步过滤。搜索时逐键输入会出现"列表闪空 → 结果回来"的闪烁。可保留旧列表直到新结果就绪(renderGeneration 已防旧结果覆盖),仅在新结果为空时才显示空态。

### 26. `KeyboardPanel` 长按弹窗每次创建全量新 View 且无复用,且存在背景色视觉 bug
`KeyboardPanel.kt:345-376`:长按字母每次新建 `LinearLayout` + N 个 `TextView` + `PopupWindow`,且 `setBackgroundColor(surfaceColor)` 与白色 `ColorDrawable` 混用(`:351` 行背景色 vs `:372` 白色背景 drawable —— 白色背景会覆盖 surfaceColor,深色主题下弹窗仍是白底,**这是个视觉 bug**:`ColorDrawable(WHITE)` 应当使用 `surfaceColor`)。弹窗也无圆角,与按键风格不一致。建议修复背景色并缓存弹窗内容视图。

### 27. 手写识别防抖 180ms + 无进行中提示
`HandwritingCoordinator.kt:167` `DEBOUNCE_MS = 180L`:笔迹停止后需等 180ms 才提交识别,期间 UI 无任何"识别中"指示(`renderHandwritingCandidates` 只在结果到达时更新)。主观体感是"写完没反应"。可在笔画停止后立刻显示轻量进度态(骨架候选或 spinner),识别返回后替换。

### 28. 候选展开面板进入时无入场动画且瞬时滚动跳转
`ZeroInputView.kt:834` + `ExpandedCandidatesView.kt:88`:非翻页进入时 `!sameInput → scrollToPositionWithOffset(0, 0)` 瞬间跳转;展开/收起无过渡。属于抛光项,优先级低,但"与输入效率无关的动画"规范允许对状态切换加 100~150ms 的淡入/位移,提升感知稳定性(AGENTS.md 第 7 节要求动态内容不导致跳动,当前展开是瞬时高度变化,恰好踩在边界上)。

### 29. `ZeroInputService.kt:1071-1072` 缩进错误(可读性)
`syncSessionPrivacy` 内两行缩进错位,纯风格问题,顺手修。

---

## 四、已确认做对的部分(不再重复列出)

设置镜像(`configuredChineseOptions` 等 `@Volatile` 字段)、`updatePrivacy` 的 `evaluatedFor` 短路、`PersonalCandidatePaging` 空页短路、`RimeInputEngine.readUpdate` 不再无条件 `comments.toList()`、`KeyboardPanel` 主题色生命周期缓存与 Enter 局部重绑、`KeyboardKeyView.setColors` 的 `KeyPalette` 相等守卫、`ExpandingRimeEngine` secondary 懒创建、`AsyncCandidateRanker` 零拷贝 CharArray + deadline、`EmojiPanelView` 颜色缓存、后台执行器全部有界 + AbortPolicy + 代次失效、所有缓存有界(LRU 64/256、TreeMap 6 页、readingHistory 64)。

---

## 五、优先级建议

**第一批(热路径纯减负,无语义风险)**:#1(去掉按键尾部 `maybeReloadLanguagePack`)、#4(`refreshHeader` 的 listOf)、#5(`CandidateStripView` 的 oldTexts 移入分支)、#6(`CandidateItemView.bind` 按身份短路 contentDescription)、#10(`setOf` 清理)、#29(缩进)。

**第二批(需要保持语义的小心改)**:#2/#3(View 推送加相等短路)、#7(个人页拼接缓存)、#8(withKaomoji 去 HashSet)、#9(路由身份直接化)、#23(内部回写跳过重 reconcile)、#25(搜索保留旧列表)、#26(弹窗背景色 bug)。

**第三批(结构性,需测试覆盖)**:#11/#12(词库索引增量维护)、#13(执行器合并与 AI 执行器懒创建)、#17(warmup 请求合并)、#19(glide 脏矩形重绘)、#22(组合中引擎热替换评估)、#24(无障碍播报去抖)。
