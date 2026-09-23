# ZeroInput 性能静态分析报告（CPU + 内存）

分析日期：2026-09-21
分析对象：`dev.zeroinput.ime` 全部 Kotlin 主源码（约 110 个 main 源文件，跨 app / ime-core / ime-ui / engine-api / engine-rime / engine-english / engine-dictionary / language-pack / security / user-data / model-scoring）
分析方法：纯静态阅读，追踪「一次按键」从 `ZeroInputService.handleKeyboardAction` 到 `InputConnection` 提交的完整链路，逐层标注 CPU 开销与对象分配；未做运行期插桩。

2026-09-21 落地状态：本次评估中的设置镜像、英文快照循环与 scratch 容器、键盘/Emoji 主题颜色缓存、Enter 局部重绑和全拼 Rime secondary 懒创建已实施。懒创建任务受会话代次保护，运行时关闭会等待 native 创建临界区，过期会话会主动释放新会话。本文中的按键对象数和耗时均为优化前静态基线，未用运行期数据替代；后续仍需在目标设备上用现有 public-fixture 插桩复测。

---

## 0. 总体结论

**架构层面的性能设计是合格的**，这一点必须先说清楚，否则改进方向会跑偏：

- 按键热路径上没有磁盘 I/O、Keystore、JNI 初始化或全量词典扫描——`onStartInput` 只安装内存降级引擎（`createImmediateEngine`），native Rime 会话由 `EngineWarmupCoordinator` 在有界单线程队列上异步创建，经令牌校验后回主线程交接。
- 个人词库查询（`QueuedPersonalizationStore`）是非阻塞的：主线程只读 LRU 缓存（容量 64），未命中时只投递后台任务并返回 `ready=false`，从不等待。
- 所有后台执行器都是有界队列 + `AbortPolicy` + 代次/令牌校验，会话结束、隐私收紧、清数据时统一取消并提升代次，没有无界任务堆积。
- 视图复用做得不错：候选按钮、表情网格、键盘按键都走 RecyclerView / 手写回收池，且 `KeyboardKeyView.setColors` 用 `KeyPalette` 相等性守卫，只在主题变化时才重建 Drawable。

**真正的问题集中在「每按键的冗余开销」**：架构把重活都搬到了后台，剩下的轻活在主线程上却被重复执行了很多次。下面按严重程度分级。

---

## 1. CPU 分析

### 1.1 单次按键的完整主线程链路

以中文全拼输入一个字母为例，主线程依次执行：

```
ZeroInputService.handleKeyboardAction(action)
  ├─ registerInteraction()              → interactionSequence++、取消粘贴同意、controller.invalidateReconversion()
  ├─ syncSessionPrivacy()               → updatePrivacy()：分配 PrivacyConfiguration + SessionPrivacy
  ├─ maybeReloadLanguagePack()     [第1次]
  │    └─ scheduleEngineWarmup() → reconcileChineseOptions()：重建 ChineseInputOptions + warmupRequest()
  ├─ modelRanking.typing()/invalidate()
  ├─ configuredHapticFeedback              ← 服务级设置镜像
  ├─ controller.handle(InputCommand.Text)
  │    └─ engine.handle(key) → RimeInputEngine.readUpdate()  ← JNI 读快照 + 逐候选构造
  │    └─ apply() → publish()
  │         ├─ PersonalCandidatePaging.publish()  ← 分配 List + HashSet
  │         ├─ rebuildRoutes()                    ← 分配 List<CandidateRoute?>
  │         ├─ InputSessionState.copy()           ← 分配
  │         └─ onStateChanged(state)
  │              ├─ inputView.renderSession(state) → ... → refreshHeader()：2× setOf()
  │              ├─ renderEngineStatus()          ← 重建 ChineseInputOptions×2 + warmupRequest()
  │              └─ refreshModelRanking()         → ModelRankingPolicy.eligible()
  └─ maybeReloadLanguagePack()     [第2次]
       └─ 同上
```

**优化前基线**：一次按键约触发 **30–45 次 SharedPreferences 读取**和 **8–12 次可避免的中等对象分配**（不含引擎必需的 `EngineSnapshot`/`Candidate`）。中端设备上这部分纯开销约 0.5–2ms，直接吃掉 16ms 帧预算的 3%–12%；这些数字是静态估算，不能替代设备测量。

### 1.2 P1 级：热路径冗余

#### (1) `maybeReloadLanguagePack()` 每按键被调用两次
`app/.../ZeroInputService.kt:546` 和 `:575` 在 `handleKeyboardAction` 的首尾各调用一次。稳态下（语言未变、无语言包）每次都走 `scheduleEngineWarmup` → `reconcileChineseOptions`（7 次 SP 读取 + `ChineseInputOptions` 分配 + `chineseEngine` 枚举扫描）+ `session.warmupRequest()`（分配 `EngineWarmupRequest`，内嵌 `ChineseInputOptions` 与 `SessionPrivacy`）。

> 两次调用语义上分别处理「按键前的状态漂移」和「按键可能改变的语言包状态」，但语言/语言包设置只可能被设置页或子类型回调改变，这些路径已经有自己的监听器并会主动触发 `scheduleEngineWarmup`。按键前后各查一次没有额外收益。

**改法**：保留按键前一次（用于在投递按键前切换语言漂移），移除 `:575` 的第二次调用；或把 `maybeReloadLanguagePack` 的结果缓存到会话令牌，仅在设置变化时失效。

#### (2) `renderEngineStatus()` 每次状态变化都重建设置快照
`app/.../ZeroInputService.kt:1148-1155`：
- `sessionChineseOptions != graph.settings.chineseInputOptions` —— 构造一个 `ChineseInputOptions`（7 次 SP 读取）
- `installedEngineWarmupContext == session.warmupRequest(...)` —— 构造一个 `EngineWarmupRequest`（含第二个 `ChineseInputOptions` + `SessionPrivacy`）
- `inputView?.renderChineseOptions(graph.settings.chineseInputOptions)` —— 第三个 `ChineseInputOptions`
- 外加 `graph.settings.chineseEngine` 的 `entries.firstOrNull` 扫描

该方法在 `onStateChanged` 回调里**每按键触发一次**。服务端已经持有 `sessionChineseOptions` / `sessionChineseEngine` 会话级缓存，比较逻辑本可以直接用它，无需反复从 SP 重建。

**改法**：`renderEngineStatus` 只比较 `sessionChineseOptions` 与 `graph.settings` 的漂移标记（由设置监听器在变化时置位），不由按键路径主动重建；`renderChineseOptions` 只在漂移标记为真时才读取并下发。

#### (3) `syncSessionPrivacy()` 每按键分配两个数据类
`ime-core/.../InputSessionController.kt:91`：`privacyPolicy.evaluate(editorInfo, configuration)` 每次都构造新的 `SessionPrivacy`，即使结果与当前完全相同（相等性检查发生在分配之后）。调用方 `graph.settings.privacyConfiguration()` 还会先分配 `PrivacyConfiguration` 并读 2 次 SP。

**改法**：`EditorPrivacyPolicy.evaluate` 接受当前 `SessionPrivacy` 并在内部做短路（先逐字段判断是否需要变化，再分配）；或让 `updatePrivacy` 先比较 `PrivacyConfiguration` 的两个字段再决定是否调用 `evaluate`。隐私语义不变：收紧时仍然立即重建引擎。

#### (4) 集合字面量在热路径上重复分配
- `app/.../ZeroInputService.kt:548`：`action !in listOf(KeyboardAction.Space, KeyboardAction.Enter)` —— 每按键分配一个 `List`。改为 `action != KeyboardAction.Space && action != KeyboardAction.Enter` 或提到 companion 常量。
- `ime-ui/.../ZeroInputView.kt:422-423`：`refreshHeader()` 里两个 `in setOf(...)`，`refreshHeader` 在每次 `renderSession`（即每按键）执行。改为枚举 `EnumSet.of(...)` 常量或直接 `==`/`||` 比较。
- `ime-ui/.../EmojiPanelView.kt:167`：`refresh()` 里的 `category in setOf(...)`。同上。

#### (5) `EmojiCatalog.search` 每次搜索都重新编译正则
`ime-ui/.../EmojiCatalog.kt:93`：`query.split(Regex("\\s+"))` 在搜索模式的每次按键时编译一个新的 `Pattern`。

**改法**：把 `Regex` 提到 companion 常量；或直接用 `query.trim().split(' ')` 过滤空串（查询串已被 `take(128).trim().lowercase()` 规范化，空白只剩空格）。

#### (6) `RimeInputEngine.readUpdate` 的九键列表被无条件求值
`engine-rime/.../RimeInputEngine.kt:226`：
```kotlin
readings = if (hasFixedSelection) emptyList() else nineKeyReadings?.choices(update.rawInput, update.comments.toList()).orEmpty()
```
Kotlin 参数严格求值，`update.comments.toList()` 在 `nineKeyReadings` 为 null（非九键布局，即绝大多数会话）时仍然分配一整份列表拷贝，每按键一次。

**改法**：把 `comments` 直接传给 `choices`（`choices` 内部本来就是按索引取，不需要 `List`），或改为：
```kotlin
readings = if (hasFixedSelection || nineKeyReadings == null) emptyList()
    else nineKeyReadings!!.choices(update.rawInput, update.comments)
```

#### (7) 候选构造的逐项字符串分配
`engine-rime/.../RimeInputEngine.kt:216-229`（`readUpdate`）与 `:172-176`（`browsePage`）：每个候选都执行
- `Candidate("rime:$index:$text", ...)` —— 字符串拼接分配
- `canonicalReading(comment)` —— `filterNot { ... }` 分配新 String

按默认页大小 8、长拼页大小可达 30+，每按键产生 16–60 次小字符串分配。

**改法**（不改变语义）：`canonicalReading` 对comment 做守卫下的快速路径——comment 只含 `[a-z]` 且不含空格/撇号时直接返回原串（`String` 判别可用 `indexOf` 循环避免分配）；候选 id 可用 `StringBuilder` 复用或改用 `index` + 前缀常量在渲染端拼。注意 `id` 是候选路由的身份，任何改动都要同步 `CandidateWindow.route` 与 `ModelRankingPolicy.eligible` 的 `startsWith` 判断。

#### (8) `PersonalCandidatePaging.publish` 每按键分配去重容器
`ime-core/.../PersonalCandidatePaging.kt:68`：`(personal + native.candidates).distinctBy(Candidate::text)` 在 `offset == 0`（即几乎所有按键）时分配一个拼接 List + 一个 HashSet。

**改法**：个人词组页为空时（`page.items.isEmpty()`）直接返回 `native`，跳过拼接与去重；非空时再用现有逻辑。注意保留现有的隐私/失效语义。

#### (9) `CandidateItemView.bind` 的身份拼接
`ime-ui/.../CandidateItemView.kt:51`：`candidate.id + "\u0000" + candidate.text` 每个可见候选每按键分配一次。可见候选通常 8–30 个。

**改法**：分别比较 `id` 与 `text`（`identityId`/`identityText` 两个字段），或缓存上一次的 `Candidate` 引用做 `==` 比较。

#### (10) `EnglishInputEngine.createSnapshot` 的序列管道
`engine-english/.../EnglishInputEngine.kt:98-115`：`asSequence().filter{}.mapIndexed{}.plus().distinctBy{}.sortedByDescending{}.mapIndexed{}.toList()` 中，`Sequence` 的 `filter`/`map` **不是 inline 函数**，每一步都分配迭代器与 lambda 对象；`distinctBy` 再分配一个 HashSet。英文逐按键约 10 次小对象分配。

**状态**：已实施。词表只有约 190 词，使用普通 `for` 循环与复用的 `ArrayList`/`HashSet` 手写收集，保留首次大小写不敏感去重和稳定排序；每次仍创建不可变候选快照所需的 `Candidate` 对象。

#### (11) 主题颜色在 bind 路径上重复解析
- `ime-ui/.../KeyboardPanel.bind()`（每个按键每次 render）：`backgroundColor(...)` ×2 + `color(colorOutline)` ×1，底层是 `MaterialColors.getColor` → `obtainStyledAttributes`。一次 shift 切换触发的 render 约 30 个按键 = 90 次主题属性解析。
- `ime-ui/.../EmojiPanelView.select()`（每个分类/分组按钮每次 refresh）：2 次 `color(...)`，约 20 个按钮 = 40 次解析。

**状态**：已实施。`KeyboardPanel` 和 `EmojiPanelView` 按附加生命周期缓存颜色，键盘文字色和 Emoji 图标色也复用缓存；重附加时失效并重新应用，`setColors` 的 `KeyPalette` 守卫继续避免 Drawable 重建。

### 1.3 P2 级：后台或低频路径

| 位置 | 问题 | 改法 |
|---|---|---|
| `user-data/.../UserLexiconRepository.kt:51-58` | `suggestionPage` 对全量词表（最多 20,000 条）做 `asSequence().filter{}.sortedWith(compareBy...thenBy...thenBy...)`，每次查询都重建比较器链并全量排序。在后台线程执行，有 64 条 LRU 缓存兜底。 | 词表按语言分片后预排序（写入时维护 `sortedBy(frequency, lastUsed)` 的有序拷贝），查询时走归并取 top-N；或对 shortcut 建前缀索引。注意仍在 `delegateLock` 内串行。 |
| `ime-ui/.../KeyboardPanel.kt:80` | `setComposing` 过去调用 `specs().flatten()` 重建全部 KeySpec，只为了给 Enter 键换标签。 | 已实施：保存 Enter 规格，只遍历现有按键重绑 Enter；`KeyboardKeyView.boundAction` 保持私有字段并提供只读访问器。 |
| `ime-ui/.../ExpressionBrowserState.kt:37-50` | `visible()` 每次 refresh 都执行 `EmojiCatalog.entries + personal.custom`（约 350 元素列表拷贝）；RECENT 分类还额外 `all.associateBy(...)` 建全量 HashMap。仅在表情面板打开时触发。 | 按 category 缓存过滤结果，仅在 category/group/query/个人数据版本变化时重算。 |
| `ime-ui/.../EmojiAdapter.kt:40-41` | `submit()` 每次 refresh 都 `entries.toList()` + `starred.toSet()` 做防御性拷贝。 | `submit` 已有 `entries == values` 早退；让 `state.visible()` 返回不可变快照（`List`/`Set` 直接持有，不再每次新建），适配器端去掉 `toList()`/`toSet()`。 |
| `ime-ui/.../ExpandedCandidatesView.kt:101` | 每次 `render` 都 new 一个匿名 `DiffUtil.Callback`。仅在展开候选面板时触发。 | 改为命名的内部类实例复用（Callback 无状态，只需替换前后列表引用）。 |
| `ime-core/.../ModelRankingPolicy.eligible()` | 每次状态变化执行多组 `any{}`/`take().filter{}` 分配。仅在实验性模型排序开启时触发。 | 早退判断前先做无分配的字段检查（已有部分短路，可把 `candidates.any { it.id.startsWith("personal:") }` 换成索引循环）。 |

---

## 2. 内存分析

### 2.1 P1 级

#### (1) 全拼中文每个会话创建两个 native Rime 会话
`engine-rime/.../RimeRuntime.kt:136-141`：非九键布局时，`createEngine` 立即创建 primary，并把 secondary 的创建器交给 `ExpandingRimeEngine`。secondary 的唯一用途是「关联读音」翻页扩展（`changePage` 走到页尾时的 `related.alternatives`）。

native 会话持有已编译 schema 与词典上下文，是输入法里最重的单块内存。**用户只要用全拼中文，内存就翻倍，而 secondary 在绝大多数会话里从未被触发。**

**状态**：已实施。`ExpandingRimeEngine` 在首次走到页尾扩展（`eligible() && alternatives == null` 且需要 `moveSource`）时，才让 `RimeRuntime` 创建第二个会话。实现保留以下约束：
- 创建必须走现有的 `engineExecutor`（不能在按键线程创建 native 会话），所以首次扩展会有一次异步等待，期间 `changePage` 返回 `consumed=false`，用户再按一次即生效——这个降级行为与现有「页尾才扩展」的语义一致。
- `close()`/`clearExpansion()`/`markFailed` 路径处理 secondary 尚未创建的状态；运行时在锁内完成创建和 `start`，`activeEngines` 只在实际会话存活时递增，过期任务会关闭创建出的会话。
- 这不改变任何隐私或正确性语义，只是把一个闲置的常驻 native 会话变成按需创建。

#### (2) 每个活动输入法服务的线程数量
一次 IME 激活期间常驻的线程：

| 线程 | 归属 | 说明 |
|---|---|---|
| `zeroinput-engine-worker` | AppGraph（共享） | Rime 初始化/语言包扫描/引擎预热 |
| `zeroinput-personalization` | AppGraph（共享） | 加密词库读写 |
| `zeroinput-local-data` | Service | emoji 历史 / 安全剪贴板索引 |
| `zeroinput-secure-clipboard` | Service | 认证后的正文解密读取 |
| `zeroinput-copy-selection` | Service | 选区读取 |
| `zeroinput-model` | Service（懒） | 实验性模型排序 |
| `zeroinput-clipboard-guard` | AppGraph（共享） | 剪贴板防护（开启时） |

加上主线程共 7–8 个。每个线程栈在 ART 上约 512KB–1MB，纯栈开销约 4–6MB，对一个键盘应用偏高。

**改法**：`local-data` / `secure-clipboard` / `copy-selection` 这三个 Service 级执行器都是「单任务、短脉冲、互不重叠」，可以合并成一个容量为 1 的共享串行执行器（任务本身已经用代次/令牌做了失效，合并不改变取消语义）。`model` 保持懒创建即可。预期可减少 2–3 个常驻线程。

### 2.2 P2 级 / 已做对的部分

- `QueuedPersonalizationStore.suggestionCache`：LRU `LinkedHashMap`，容量 64，清数据/隐私变化时 `clear()`。**有界，正确**。
- `ZeroInputService` 的 `recentEmojiCache` / `secureClipboardCache` / `personalExpressionCache`：在 `onFinishInputView` / `endInputSession` / `clearLocalPanelCaches` 时清空。**有界，正确**。
- `ExpandingRimeEngine.lastPages`：HashMap 以 source 索引为键，扩展上下文有限。**有界**。
- `RimeInputEngine.readingHistory` / `selectedIndices`：分别容量 64。**有界**。
- `AsyncCandidateRanker`：零拷贝 `CharArray`、deadline 80ms、generation 守卫、结果单槽位合并。**设计良好**。
- `KeyboardKeyView.setColors` 的 `KeyPalette` 相等性守卫避免重复创建 Drawable。**做对了**。
- `EmojiCatalog` 约 350 个静态 `EmojiEntry`（含 `searchText` 小字符串），常驻约几十 KB，可接受。
- 所有 `InputConnection` / `Activity` / `View` 引用：服务在 `onDestroy` 关闭全部观察者并 `inputView?.release()` 清回调和个人绑定；`EngineWarmupResultDelivery` 保证已投递但未被主线程消费的引擎不成为孤儿。**生命周期处理严谨**。
- `InputSession` 不持有 `InputConnection`，只持有 `SessionConnectionBinding`（弱比较 `=== currentInputConnection`）。**正确**。

---

## 3. 优先级建议

按「收益 / 改动风险」排序，建议的实施顺序：

**第一批（纯主线程减负，改动局部、无语义变化）**
1. 移除 `handleKeyboardAction` 末尾的第二次 `maybeReloadLanguagePack()` 调用。
2. 热路径集合字面量改常量/直接比较（`ZeroInputService:548`、`ZeroInputView:422-423`、`EmojiPanelView:167`）。
3. `EmojiCatalog.search` 的 `Regex` 提为常量。
4. `RimeInputEngine.readUpdate` 的 `comments.toList()` 无条件求值。
5. `PersonalCandidatePaging.publish` 空个人页时短路。
6. `CandidateItemView.bind` 身份比较改为双字段。

**第二批（需要小心保持语义）**
7. `renderEngineStatus` 改用漂移标记而非每按键重建设置快照。
8. `updatePrivacy` / `evaluate` 的分配短路（隐私收紧语义必须保持）。
9. `EnglishInputEngine.createSnapshot` 序列改手写循环（已实施）。
10. `KeyboardPanel` / `EmojiPanelView` 主题颜色缓存（已实施）。
11. `KeyboardPanel.setComposing` 只重绑 Enter 键（已实施）。

**第三批（结构性，需要测试覆盖）**
12. `ExpandingRimeEngine` 的 secondary 会话懒创建（已实施，仍需设备内存复测）。
13. Service 级执行器合并（减少常驻线程）。
14. `UserLexiconRepository.suggestionPage` 的查询索引（后台 CPU 收益）。

---

## 4. 验证建议

静态分析只能指出「看起来浪费」，落地前必须测量：

- **按键耗时**：项目已有 public-fixture 插桩（见 `docs/architecture.md`「Input remains synchronous through ime-core; public-fixture instrumentation measures key dispatch, editor updates and the next frame separately」）。用它对比改前/改后「dispatch → editor update → next frame」三段耗时，重点看中端设备（2GB RAM 级）的 P95。
- **分配量**：用 Android Studio Profiler 的 allocation tracker 抓「输入 20 个拼音字母」场景，统计 `ZeroInputService`/`InputSessionController`/`RimeInputEngine` 三个包的新增对象数；目标是把每按键分配从约 40–60 降到 15–20（EngineSnapshot + Candidate 链是下限）。
- **内存**：`dumpsys meminfo dev.zeroinput.ime` 对比「全拼中文会话激活」时的 native heap，验证 secondary 会话懒创建的实际收益（预计是本次清单里最大的一项）。
- **回归**：`ime-core/src/test/.../InputSessionControllerTest.kt`（873 行）覆盖了会话/隐私/降级路径；`engine-rime` 的契约测试要覆盖 secondary 会话懒创建后的首次扩展行为；`app/src/androidTest` 下的 `InputPipelineTest` / `KeyboardExperienceTest` 做端到端回归。

---

## 5. 一句话总结

这个项目的并发与隐私边界设计得比大多数输入法应用都严格，按键热路径上没有真正的阻塞点；本次已消除配置读取、英文序列管道、主题颜色解析、组合状态全量重绑和全拼会话闲置 secondary 的常驻分配。仍待设备测量确认实际 P95、分配量与 native 内存收益；Service 执行器合并和用户词库查询索引仍未实施。
