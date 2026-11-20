# ZeroInput 当前代码缺陷审查报告

后续处置：本报告保留原审查基线的缺陷记录；B-01、B-02、B-03 已完成修复和回归验证，详见[修复与验收记录](2026-11-19-current-code-audit-fixes.md)。

审查日期：2026-11-19。审查基线：`33ff309f672887ffeaddf134c709dd26070fcc2f`。

本次确认 **3 个 bug：高严重度 1 个、中严重度 2 个**。均有实际生产调用链和本地定向复现，尚未修改业务代码。既有单元测试、隐私检查与 Debug Lint 通过，说明这三条失败路径没有被现有断言充分覆盖，不能据此认为缺陷不存在。

## 1. 范围、方法与判定标准

审查范围为仓库自有代码、相关配置和测试，覆盖 12 个模块；主源码约 440 个 Kotlin/C++/头文件、5.27 万行。采用模块梳理、关键调用链精读、生命周期与异常路径追踪、既有测试交叉核对及定向探针验证。第三方依赖源码未逐行审计。

开始时核对了 `AGENTS.md`、`docs/project-progress.md`、架构与威胁模型，以及引擎、安全剪贴板、页面引用、资源管理等对应 ADR。工作区起始无未提交改动；审查过程中未改动生产实现、依赖、权限或持久化格式。

| 范围 | 重点检查内容 | 结果 |
| --- | --- | --- |
| `ime-core`、`engine-api` | 会话、隐私收紧、候选路由、提交失败、组合编辑、模型排序、个人候选分页 | 确认 B-01 |
| `engine-english`、`engine-rime`、`engine-dictionary` | 组合到提交的状态转换、native 初始化/降级/关闭、JNI 边界、词典解析、滑行检索 | B-01 的引擎状态变更链得到确认；未另确认独立缺陷 |
| `app/input`、IME 生命周期 | 编辑器绑定、独立草稿、长度限制、认证返回、旧会话与异步结果失效 | 确认 B-01 的草稿可达场景 |
| `ime-ui`、设置与颜文字管理 | 触摸/取消、候选、草稿光标、文件选择器返回、前后台状态、资源清理 | 确认 B-02、B-03 |
| `security`、`user-data` | 加密文件、一次性认证、私有库、导入导出、删除代次和排队写入 | 本轮未确认新的独立 bug |
| `language-pack`、公开词库/模型资源 | 有界解析、路径/哈希、原子发布、读者租约和取消 | 本轮未确认新的独立 bug |
| `app/ai`、`ai-api` | 请求代次、流事件、工具审批、SAF 读写、远程连接、前台任务、设备代理和页面引用 | 本轮未确认新的独立 bug |
| `model-scoring`、手写/滑行协调器 | 张量/会话释放、取消、回调、资源版本与线程所有权 | 本轮未确认新的独立 bug |

“未确认”仅表示本轮没有得到足够证据，不代表对应代码不存在其他缺陷。静态审查和有限测试无法保证穷尽所有 bug。

严重等级：

- **P0 / 严重**：可造成广泛不可恢复的数据损坏、重大隐私泄露或系统性不可用。
- **P1 / 高**：有可达路径造成输入丢失、重要安全边界失效或核心功能严重受损。
- **P2 / 中**：明确的功能失败或交互错误，影响局部流程，通常存在绕行或恢复方式。
- **P3 / 低**：局部表现、提示或轻微资源问题，影响有限。

只有当前实现中有依据并完成核验的问题计入统计。同一根因的多个入口合并为一项；历史报告已修复项、已撤回判断和 ADR 明确接受的行为不重复计数。

## 2. 确认的 bug

### B-01：候选提交被拒绝后仍清空组合，导致未提交输入丢失

**严重程度：P1 / 高。类别：状态机逻辑、失败处理、长度边界。验证状态：两个定向探针已复现。**

**所在文件与位置：**

- [InputSessionController.kt:626](K:/zeroInput/ime-core/src/main/kotlin/dev/zeroinput/ime/core/InputSessionController.kt:626)：`apply()` 调用 `commitPredictableText()`，没有在返回 `false` 时中止状态发布。
- [InputSessionController.kt:653](K:/zeroInput/ime-core/src/main/kotlin/dev/zeroinput/ime/core/InputSessionController.kt:653)：无条件发布引擎提交后的快照。
- [EnglishInputEngine.kt:49](K:/zeroInput/engine-english/src/main/kotlin/dev/zeroinput/engine/english/EnglishInputEngine.kt:49)：`selectCandidate()` 在编辑器接受提交前，已清空引擎缓冲并返回空快照。
- [LocalDraftInput.kt:297](K:/zeroInput/app/src/main/kotlin/dev/zeroinput/ime/input/LocalDraftInput.kt:297)：草稿容量不足时明确拒绝提交。
- [LocalDraftInput.kt:155](K:/zeroInput/app/src/main/kotlin/dev/zeroinput/ime/input/LocalDraftInput.kt:155)：后续完成组合时重新清除长度失败标志。

**问题描述与复现：**

1. 输入英文 `hel`，编辑器保有该组合；让 `EditorConnection.commitText()` 明确返回 `false`。
2. 点击首个候选，实际尝试提交 `hel `，编辑器拒绝。
3. 控制器和真实英语引擎的 `snapshot.isComposing` 均变为 `false`，编辑器的旧组合仍为 `hel`。
4. 再输入 `x`，控制器将编辑器组合改成 `x`，原来的 `hel` 消失。

这不只依赖异常的外部编辑器。本项目自身的有界 `LocalDraftInput` 就能触发：使用容量为 3 的公开测试草稿，先写入 `ABC`，再组合 `x` 并选择候选；容量检查拒绝该候选，但组合已经消失。此后调用 `submittedChars()`，返回的是非空的 `ABC` 字符数组，丢失的 `x` 不再阻止提交。实际产品使用更大的容量，同样的边界仍然存在。

**产生原因：**

引擎的候选选择是有副作用的：在返回 `EngineUpdate` 时已经推进或清空内部组合。`apply()` 只用提交结果控制学习与再转换记录，却无条件接受提交后的快照。外部编辑器、控制器和引擎因此失去一致性。草稿随后完成组合时重置 `lengthLimitReached`，又使已丢弃的组合无法继续阻止不完整内容的提交。

**影响与定级依据：**

可丢失尚未确认的输入，影响普通输入和共享该控制器的独立草稿。已有文本不一定损坏，也未发现越权读取，但输入正确性直接受损，故评为高严重度。英语路径和本地草稿路径已实测；Rime 的真实拒绝提交路径未进行设备复现，不把其具体表现当作已测结论。

**建议修复方案：**

- 将“编辑器接受提交”设为推进控制器可见状态的必要条件；提交被明确拒绝时保留可恢复的组合/待提交结果，并允许用户重试或取消。
- 同时处理引擎已变更的问题，保证引擎、候选路由和可见组合一致；仅提前 `return` 或保留旧 UI 快照不足以修复。
- 草稿的失败状态应保持到用户恢复容量、重新成功完成组合或显式取消；不能因下次调用完成组合就把不完整内容当作成功草稿。
- 区分“明确未接受”和“平台调用结果不确定”。对后者不可自动重放提交，避免重复输入。
- 增加候选点击、空格提交、容量不足及恢复后重试的回归测试；断言文本不丢失、不会重复提交，失败时不学习。

### B-02：颜文字导出从文件选择器返回时被静默丢弃，产生空文件

**严重程度：P2 / 中。类别：界面交互、Activity 生命周期、异步操作调度。验证状态：Robolectric 生命周期探针已复现。**

**所在文件与位置：**

- [ExpressionManagerActivity.kt:48](K:/zeroInput/app/src/main/kotlin/dev/zeroinput/ime/expressions/ExpressionManagerActivity.kt:48)：导出结果回调直接执行 `runOperation()`。
- [ExpressionManagerActivity.kt:89](K:/zeroInput/app/src/main/kotlin/dev/zeroinput/ime/expressions/ExpressionManagerActivity.kt:89)：只有 `onResume()` 才把 `foreground` 设置为 `true`。
- [ExpressionManagerActivity.kt:95](K:/zeroInput/app/src/main/kotlin/dev/zeroinput/ime/expressions/ExpressionManagerActivity.kt:95)：进入选择器时 `onPause()` 将其设置为 `false`。
- [ExpressionManagerActivity.kt:243](K:/zeroInput/app/src/main/kotlin/dev/zeroinput/ime/expressions/ExpressionManagerActivity.kt:243)：前台标志为假时静默返回，不保留 URI 或安排重试。

**问题描述与复现：**

1. 在自定义颜文字管理页选择导出，确认后打开 `CreateDocument` 文件选择器。
2. 原 Activity 暂停/停止，`foreground` 已为 `false`。
3. 选择文件位置并返回。Activity Result 可以在页面重新启动后、恢复到 `onResume()` 前交付。
4. 导出回调已拿到 URI，但被 `runOperation()` 的前台判断丢弃；`onResume()` 只刷新列表，没有待处理的导出请求。

探针实际启动该页的导出 launcher，模拟暂停、停止、重新启动，在恢复前交付成功的文件结果，再恢复页面。结果：导出加密器调用次数为 **0**，测试目标文件长度为 **0 字节**。文件已创建，但导出内容未写入，也没有失败反馈。

**产生原因：**

Activity Result 的结果交付生命周期与业务采用的 `foreground` 标志不同步。回调使用只接受已恢复页面操作的入口，却没有暂存 picker 结果。该页的导入流程已有 `pendingImportUri` 和恢复后 drain 机制，导出没有对应处理。即使简单放宽回调门禁，也需要避免恢复时的列表刷新取消或覆盖导出任务。

**影响与定级依据：**

用户完成正常导出操作后得到空文件，备份功能失败。原有颜文字库仍保留，未确认存在源数据删除，故评为中严重度。生命周期行为已用真实 Activity Result 注册与分发机制验证；本轮未在真机 DocumentsUI 上重复操作。

**建议修复方案：**

- 暂存当前导出请求的 URI，页面恢复且工作执行器可用后再消费；不要在回调中直接丢弃请求。
- 将恢复后的列表刷新和待导出任务明确串行安排，确保刷新不会撤销已确认的导出。
- 绑定本次请求、取消和页面失效状态；对不能继续的请求提供明确失败反馈，避免留下看似成功的空备份。
- 增加恢复前交付、恢复后交付、用户取消及页面重建的回归验证；成功断言必须检查导出器确实执行、文件可重新导入，不能只检查无异常。

### B-03：隐写编辑页忽略中间光标，将组合显示在末尾

**严重程度：P2 / 中。类别：界面显示、光标交互、组合与已提交文本映射。验证状态：实际工作区与草稿链路探针已复现。**

**所在文件与位置：**

- [StegoWorkspace.kt:535](K:/zeroInput/app/src/main/kotlin/dev/zeroinput/ime/textstego/StegoWorkspace.kt:535)：`fieldDisplays()` 总将组合内容拼到已提交文本尾部。
- [StegoWorkspace.kt:502](K:/zeroInput/app/src/main/kotlin/dev/zeroinput/ime/textstego/StegoWorkspace.kt:502)：工作区主文本构造存在相同拼接方式。
- [StegoPanelView.kt:143](K:/zeroInput/ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/StegoPanelView.kt:143)：渲染字段光标时未传组合长度，使用默认值 0。
- [LocalDraftInput.kt:103](K:/zeroInput/app/src/main/kotlin/dev/zeroinput/ime/input/LocalDraftInput.kt:103)：中间光标移动逻辑可达；实际提交按草稿 caret 插入，而非末尾追加。

**问题描述与复现：**

1. 打开隐写工作区，在公开文本字段已有已提交内容 `AB`。
2. 通过当前版本的光标意图将 caret 移至偏移 1，即 `A|B`。
3. 输入仍在组合中的英文 `x`。字段显示为 **`ABx`**。
4. 按 Enter 确认组合，实际草稿成为 **`AxB`**。

探针通过真实 `StegoWorkspace.type()`、`LocalDraftInput` 和英语引擎执行该流程；仅通过反射读取生成的字段展示模型，没有替换显示算法或修改被测状态。可见组合位置与最终插入位置不一致。

**产生原因：**

草稿缓冲区已经支持任意 caret 插入，但隐写工作区仍按只在末尾输入的方式构造展示文本。`DraftTextView` 需要组合长度才能正确绘制光标并把触摸偏移映射回已提交文本，当前调用也未提供该信息。两个映射没有随着草稿的中间编辑能力同步更新。

**影响与定级依据：**

公开文本以及开启完整显示的秘密文本等草稿字段在中间编辑时会误导用户；确认前后内容位置跳变，可能造成错误修改。探针确认的是公开文本字段，其他共用分支的影响来自代码追踪。未确认此问题会越过隐藏/显示或加密边界，故评为中严重度。

**建议修复方案：**

- 在当前 caret 处插入 preedit 构造显示内容，并让可见组合、光标和触摸偏移使用相同的长度与位置模型。
- 为字段 UI 模型携带必要的组合元信息，向 `renderCaret()` 传入准确的 preedit 长度。
- 可参考现有 [AiWorkbenchPanelView.kt:287](K:/zeroInput/ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/AiWorkbenchPanelView.kt:287) 的按 caret 插入组合及光标处理，复用已明确的映射规则。
- 保持秘密字段的显式显示限制；覆盖首部/中间/末尾、中文格式化组合、代理对/emoji、组合期间再次点击光标以及隐藏/显示切换。

## 3. 验证结果与证据

### 3.1 既有检查

本轮执行并成功完成：

```powershell
./gradlew.bat testDebugUnitTest :engine-api:test :engine-english:test `
  :engine-dictionary:test :ai-api:test privacyCheck :app:lintDebug `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel --console=plain
```

结果为 `BUILD SUCCESSFUL in 1m 59s`，410 项任务中 35 项执行、2 项来自缓存、373 项为 up-to-date。**不把缓存或 up-to-date 的测试描述为本轮全部重新执行。** Debug Lint 文本报告为 `No issues found.`。

按该命令对应的 Debug/JVM XML 结果统计如下，不混入已有 Release 结果，也不包含本轮探针：

| 模块 | 用例数 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: |
| `app` | 544 | 0 | 2 |
| `ime-core` | 203 | 0 | 0 |
| `ime-ui` | 50 | 0 | 0 |
| `engine-api` | 5 | 0 | 0 |
| `engine-english` | 14 | 0 | 0 |
| `engine-dictionary` | 35 | 0 | 0 |
| `engine-rime` | 72 | 0 | 0 |
| `language-pack` | 64 | 0 | 0 |
| `security` | 29 | 0 | 0 |
| `user-data` | 93 | 0 | 0 |
| `model-scoring` | 20 | 0 | 0 |
| `ai-api` | 36 | 0 | 0 |
| **合计** | **1,165** | **0** | **2** |

即 1,163 项通过，2 项因缺少显式临时凭据而跳过，分别为 `AiProviderLiveHostTest` 和 `AiProviderEditorTest` 中的真实服务用例。未访问或申请真实用户凭据。

### 3.2 本轮定向复现

在 Robolectric API 28 下运行 4 项公开固定样本探针，全部完成，0 失败、0 跳过：

| 探针 | 证明的现象 | 对应 bug |
| --- | --- | --- |
| `rejectedCandidateCommitLosesTheUncommittedWord` | 编辑器拒绝后引擎/控制器组合消失，下一键替换旧组合 | B-01 |
| `fullLocalDraftForgetsRejectedCompositionAndThenAllowsSubmission` | 本地草稿因容量拒绝后，随后仍返回缺失组合的可提交正文 | B-01 |
| `documentExportResultIsDroppedBeforeResume` | 结果在恢复前交付后导出器执行 0 次、目标文件 0 字节 | B-02 |
| `stegoPreeditIsShownAtTheEndInsteadOfTheActualCaret` | 中间输入显示 `ABx`，确认后的真实正文为 `AxB` | B-03 |

探针断言的是**当前错误现象确实发生**；其测试通过不表示 bug 已修复。临时测试源已移出正常源码目录，用一次性 Gradle init 脚本加载；生产代码保持原样。最终恢复普通测试配置并通过 `:app:testDebugUnitTest --rerun` 实际重跑完整 app 测试：542 项通过、2 项凭据用例跳过，0 失败/错误，正常结果中不含审查探针。该次构建为 `BUILD SUCCESSFUL in 26s`。

本机证据在 ignored 的 `.tmp-evidence/audit-20261119-current/` 中：

- [基线检查日志](K:/zeroInput/.tmp-evidence/audit-20261119-current/baseline-checks.log)
- [定向探针源文件](K:/zeroInput/.tmp-evidence/audit-20261119-current/probes/CurrentAuditProbeTest.kt)
- [探针独立加载脚本](K:/zeroInput/.tmp-evidence/audit-20261119-current/probe.init.gradle)
- [探针最终重跑日志](K:/zeroInput/.tmp-evidence/audit-20261119-current/probe-replay.log)
- [探针 XML 结果](K:/zeroInput/.tmp-evidence/audit-20261119-current/probe-results.xml)
- [正常 app 测试恢复日志](K:/zeroInput/.tmp-evidence/audit-20261119-current/app-final-tests.log)
- [对应任务的单元测试统计](K:/zeroInput/.tmp-evidence/audit-20261119-current/test-summary.json)

重新复现当前缺陷的命令：

```powershell
./gradlew.bat -I .tmp-evidence/audit-20261119-current/probe.init.gradle `
  :app:testDebugUnitTest --tests dev.zeroinput.ime.audit.CurrentAuditProbeTest --rerun `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true --no-parallel --console=plain
```

上述证据为本机审查材料，不包含真实输入内容、私有库或服务凭据，不作为生产源码提交。

## 4. 已排除判断与验收限制

- 上次报告中的默认设备类别全授权、个人数据刷新丢失模型排序已在当前代码修复，不计入本报告。组合显示 offset 映射、搜索草稿截断、候选锚点、固定密钥位数空列表等已撤回判断也未重复计数。B-03 是独立的隐写字段拼接路径，并非上述搜索草稿问题。
- `DeviceActionCatalog.find()` 会继续查询阶段 6 工具目录，因此不能把已支持的阶段 6 调用误报为必然空引用。
- `PublicResourceStore` 在租约仍存活时保留资源，后续资源变更才回收无引用文件，与 ADR 0021 明确约定一致，不计为本轮泄漏 bug。
- 核对了认证授权、删除代次、异步取消、worker 队列和 JNI 资源所有权，但没有新增可稳定证明的独立空引用、类型错误、越权或并发泄漏条目；不以“可能存在竞态”凑数。
- 本轮未新启动模拟器/真机，未执行新的全尺寸/主题/旋转视觉验收、跨应用真实编辑器测试、DocumentsUI 实机流程或故障注入设备测试。Robolectric 复现不替代这些平台验收。
- 虽使用 `-PrequireRime=true`，本轮没有重新完成三个 ABI 的 APK/Release 打包和真实 native 行为验收；不将既有发布记录当作本轮新执行的结果。
- 两项真实 AI 服务用例缺少临时测试凭据而跳过。没有实际操作通信、联系人、日历、相机等用户数据能力，也未对远程服务或第三方代码作完整渗透/供应链审计。

## 5. 严重等级统计与建议顺序

| 等级 | 数量 | 编号 |
| --- | ---: | --- |
| P0 / 严重 | 0 | — |
| P1 / 高 | 1 | B-01 |
| P2 / 中 | 2 | B-02、B-03 |
| P3 / 低 | 0 | — |
| **合计** | **3** | **3 项均已定向复现，均待修复** |

建议先修 B-01 的提交一致性与草稿长度失败路径，再修 B-02 的导出生命周期，最后修 B-03 的组合显示与光标映射。修复后将探针改为正确行为断言，补充拒绝后重试、容量恢复、文件可导入性及实际触摸定位验收，再运行对应模块测试、隐私检查与 Lint。
