# ZeroInput AI 功能实现评估与改进建议

评估日期为 2026-11-19。第 1 至 9 节保留评估时的判断与复现条件。第一阶段的首次交付见[第 10 节](#phase-one)，首次复查见[第 11 节](#phase-one-review)，剩余项的本机验收和外部验证条件见[第 12 节](#local-remaining)。

评估对象为当日本地工作区，包含开始分析前已有的未提交改动。Git HEAD 为 `33ff309f672887ffeaddf134c709dd26070fcc2f`，仅用于定位仓库基线，不代表被审阅的全部内容已包含在该提交中。

本报告整理本轮源码审阅、相关自动化测试和定向复现的结果。评估阶段未编辑生产源码、配置、依赖、权限或持久化格式；本次整理仅新增报告并在现有权威进度文件中登记入口。下文的「当前」均指评估当天的工作区。

## 1. 总体判断

当前 AI 已具备较完整的功能基础：聊天、多协议、多模态附件、角色与显式记忆、声明式 Skill、文件工具、MCP、搜索、后台任务和设备代理均有实际实现。

优先改进方向是对话连续性、失败恢复和回答完整性，再扩展工具协作能力。本轮确认 5 项问题，建议优先级为 P1 三项、P2 两项；协议兼容性另列为待验证风险，不计入已复现的远程服务故障。

未确认到 P0 级越权或数据外泄问题。源码中的默认关闭、显式发送、独立草稿、有界请求、独立加密存储和代次撤销等边界已有较多实现；本轮结论仍受真实服务及设备环境验证范围限制。

## 2. 评估范围与判定方式

### 2.1 范围

| 范围 | 审阅重点 |
| --- | --- |
| `ai-api` | 请求和响应模型、上下文预算、角色/记忆/Skill、压缩、工具与设备动作契约 |
| 应用 AI 平台代码 | 工作台、独立聊天、队列、多协议编码/解析、联网传输、附件、模型探测 |
| 上下文与任务编排 | 上下文库、Skill 管理、压缩、文件工具、MCP/Brave、后台任务和通知 |
| 页面与设备能力 | 页面引用、设备协调器、审批、前台绑定、无障碍操作、截图脱敏和阶段 6 数据能力 |
| `ime-ui` 与 IME 接线 | 聊天展示、上下文检查清单、回答选择与插入、入口导航 |
| `user-data` / `security` | AI 配置、会话、上下文库、Skill、远程连接的加密持久化与清除边界 |
| 配置与文档 | Manifest、相关模块构建配置、架构、威胁模型、权威进度与 AI ADR |

### 2.2 证据与优先级

- **逻辑复现**：使用当前编译产物执行公开固定样本，观察确定性结果；不等同于 Android 真机验收。
- **静态确认**：生产调用链和状态变更足以说明条件成立时的行为，仍标明缺少的平台复现。
- **待验证风险**：存在实现依据，但尚未用真实服务或设备确认具体失败。
- **能力缺口**：当前未提供或操作闭环不足的能力，不自动视为 bug。
- **既定边界**：ADR 和项目规则明确限制的行为，不列为漏实现或安全缺陷。

P1 表示应优先修复的输入/草稿丢失或核心对话受损问题；P2 表示明确的局部功能错误。优先级用于安排后续工作，不表示修复已获实施授权。

## 3. 功能覆盖与成熟度

| 功能领域 | 当前实现 | 主要评价 |
| --- | --- | --- |
| 键盘 AI 工作台 | 独立草稿、流式回答、问答/改写/翻译、结果分段插入、附件、页面引用 | 功能较齐，插入失败处理不足 |
| 独立 AI 聊天 | 连续对话、待发队列、模型切换、历史管理、上下文选择 | 连续性和失败恢复存在明显问题 |
| 模型接入 | Chat Completions、Responses、Anthropic Messages；主动获取模型和连接测试 | 协议支持较完整，具体模型能力描述不足 |
| 角色、记忆、Skill | 主动创建、隔离保存、预览勾选、声明式 Skill、上下文压缩及撤销 | 边界清晰，操作链路偏手工 |
| 文件工具 | 用户选定文件的读取、搜索、新文件另存；逐次审批 | 多文件识别和审批体验需要完善 |
| MCP / Brave | 主动连接、审核工具目录、显式选择和审批 | 每个任务当前只接入一个远程连接 |
| 设备代理 | 前台窗口绑定、固定动作、分阶段数据能力、审批及撤销 | 防护实现较充分，真实设备覆盖不足 |
| 后台任务与系统助手 | 有界后台任务、通用状态通知、系统助手文字入口 | 系统助手能力和回答处理与完整聊天不一致 |
| 虚拟屏 | 固定公开自有样例的普通 API 验证 | 目前属于可行性验证 |

## 4. 确认的问题

### AI-B01：上下文满额后未纳入最新问答，缺少明确提醒

**优先级：P1。证据：当前编译产物已完成逻辑复现。**

当前发送上下文最多包含 12 条历史消息，并与其他工作上下文共享 16,384 字符预算。`append()` 先保留之前选中的历史，再尝试加入新问答；`replace()` 因预算超限返回 `false` 时，调用方忽略该结果。

复现结果：

1. 连续追加 8 轮公开的短问答，每轮包含一条用户消息和一条助手消息。
2. 第 6 轮结束时，选中索引为 0 至 11，对应前 6 轮。
3. 第 7、8 轮结束后，选中的仍是 0 至 11；准备下一次请求时，历史仍只有前 6 轮。

因此，第 7 轮之后的新讨论可能没有进入后续上下文。长回答或较多参考材料会更早触发字符预算限制。界面目前主要显示上下文数量，没有明确说明“刚完成的问答未纳入”，用户会感知为连续聊天突然失去最新记忆。

保留用户明确勾选的旧内容有其必要性；问题在于追加失败没有成为可见、可处理的状态。

建议：显示本轮未加入后续上下文的状态，并直接提供已有的“选择最近历史”“调整勾选”“审核压缩”入口。保留用户对实际发送内容的控制。

后续验收：覆盖第 6/7/8 轮、字符预算先满、角色/记忆/引用占用预算、手工勾选后追加、压缩或重新选择后的恢复；同时检查发往模型的实际历史和界面提示。

源码依据：

- [AiContextSelection.kt:82](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiContextSelection.kt)：追加新问答并忽略替换失败。
- [AiContextSelection.kt:90](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiContextSelection.kt)：超限时保留原选择。
- [AiContext.kt:17](../../ai-api/src/main/kotlin/dev/zeroinput/ai/api/AiContext.kt)：上下文字符数及消息数上限。

### AI-B02：独立聊天失败后，当前问题和待发问题缺少恢复入口

**优先级：P1。证据：队列清空已完成逻辑复现，输入框及记录路径已静态确认。**

发送被接受后，面板立即清空输入框；请求失败或取消时，`queue.failed()` 清空待发队列。当前用户问题只有成功完成后才加入聊天记录，因此网络超时、鉴权失败等情形下，当前问题和已排队问题都缺少可用的恢复入口。

队列探针结果：已有一个待发问题时调用 `failed()`，待发数量变为 0，此后无法取回该问题。另有一条相关路径：本地会话保存失败会调用 `turnSettled(false)`，同样清空待发队列；但已完成问答已进入当前内存会话，不能将其描述为当前回答也一并丢失。

建议：停止自动派发后，保留有界、仅当前有效前台会话可访问的失败消息和待发内容，提供“编辑后重发”“重新发送”“丢弃”。发送仍由用户主动触发，退出、锁屏及既有失效条件继续清除临时内容。

后续验收：覆盖超时、鉴权失败、取消、存储失败、排队多条、队列满额和重发；断言没有自动重试、重复请求，且失效后不能恢复旧秘密内容。

源码依据：

- [AiAssistantActivity.kt:171](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiAssistantActivity.kt)：发送被接受后清空输入框。
- [AiAssistantPanelView.kt:254](../../ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/AiAssistantPanelView.kt)：清空当前草稿。
- [AiChatQueue.kt:56](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiChatQueue.kt)：失败时清空待发队列。
- [AiWorkbenchController.kt:363](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiWorkbenchController.kt)：成功后构建问答记录及保存结果回调。

### AI-B03：回答插入未处理编辑器拒绝，结果被提前消费

**优先级：P1。证据：生产调用链静态确认，尚未做设备拒绝提交复现。**

IME 先调用 `consumeResult()` 清除可插入结果，再取得输入连接并调用 `commitText()`。提交的布尔返回值被忽略，随后关闭 AI 面板。

当编辑器明确拒绝提交，或连接在提交前失效时，用户可能没有得到文字，也失去原地重试机会。原有保存历史默认关闭，不能依赖持久化会话兜底。

建议：将“准备插入”和“确认消费”分开，实际接受提交后再消费结果。失败时保留当前有效绑定内的结果，提供显式重试或取消。连接变化或结果失效时继续撤销，结果不确定时不得自动重放提交。

后续验收：覆盖 `commitText()` 返回 `false`、连接无法解析、提交前会话失效、成功提交、防重复点击和显式重试；结果不得插入之后的新编辑器。

源码依据：

- [ZeroInputService.kt:1497](../../app/src/main/kotlin/dev/zeroinput/ime/ZeroInputService.kt)：AI 回答插入顺序及未处理提交返回值。
- [AiWorkbenchController.kt:341](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiWorkbenchController.kt)：返回文本前清除可插入结果。

### AI-B04：推理标签过滤误删正常代码和字面内容

**优先级：P2。证据：当前编译产物已完成逻辑复现。**

工作台无条件使用 `<think>` / `</think>` 拆分回答，没有区分推理标签与代码块、引用中的字面标签。

复现输入为包含 `<think>keep literal content</think>` 的 XML 代码块。拆分后，`keep literal content` 被归入推理区域，正常答案只保留空代码块。涉及 XML、模板或标签说明的回答因此会被破坏；流式拆分也需要处理相同语义。

建议：优先使用协议独立的推理字段；内嵌标签兼容应有明确适用条件，并正确识别字面代码。继续保持推理与可插入答案分离。

后续验收：覆盖围栏代码块、行内代码、普通引用、分片跨标签、真实内嵌推理、结构化推理与未闭合标签；同时断言显示和最终插入的正文。

源码依据：

- [AiThinkingText.kt:51](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiThinkingText.kt)：按标签拆分全文。
- [AiWorkbenchController.kt:308](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiWorkbenchController.kt)：完成事件无条件进行拆分。

### AI-B05：系统助手静默截断长回答，无法从完整助手恢复全文

**优先级：P2。证据：生产路径静态确认，尚未做 Android UI 定向复现。**

Provider 允许的回答上限为 32,768 字符，系统助手视图只保留前 8,192 字符，仍显示完成。点击打开完整助手时，会先清除当前内容，再启动新的聊天，没有转移完整回答。

正常长回答的代码、步骤或结论可能被截断，用户也缺少全文恢复入口。系统助手另有输出处理不一致：未经过工作台的标签拆分，内嵌推理标签可能原样展示；这属于处理规则不一致，本轮未据此确认隐私泄露。

建议：在既有有界输出范围内完整滚动显示，或明确提示截断并提供受控全文查看；统一各入口的回答和推理处理规则。

后续验收：覆盖 8,192 字符前后及合法输出上限、长代码块、完成提示、打开完整助手、关闭/锁屏清除和输出处理一致性。

源码依据：

- [AiNativePromptView.kt:41](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiNativePromptView.kt)：截断为 8,192 字符。
- [AiNativeSession.kt:50](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiNativeSession.kt)：清除后打开新助手页面。
- [AiNativeSession.kt:88](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiNativeSession.kt)：完成事件直接展示并标记完成。
- [AiModels.kt:158](../../ai-api/src/main/kotlin/dev/zeroinput/ai/api/AiModels.kt)：合法输出字符数上限。

## 5. 体验、能力缺口与兼容性风险

以下条目不计入前述 5 项缺陷；其中有些是当前产品范围，有些需要真实服务验证。

| 编号 | 方向与现状 | 改进建议 |
| --- | --- | --- |
| AI-G01 | 多文件任务只向模型提供 `file1`、`file2` 等编号；文件名映射仅留在本地。审批主要展示原始参数，多文件操作难以辨认 | 审批就地显示文件名、目标和读取范围；对拟发送的必要元数据明确预览，URI 继续留在本地 |
| AI-G02 | 图片/音频有模型能力声明，工具调用、推理参数和输出预算缺少完整的模型级描述 | 增加明确的能力配置，使聊天、探测、工具和设备代理使用一致规则；能力检测仍由用户主动发起 |
| AI-G03 | 工具、设备和探测路径使用固定 token 参数；推理模式按协议编码，未充分区分具体模型。真实服务兼容性尚未验证 | 建立代表性服务与模型矩阵，检查 token 参数、推理和工具能力；明确错误，不自动回退或重复付费请求 |
| AI-G04 | 缺少失败消息恢复、编辑已发送问题和显式重新生成等完整闭环 | 先完成失败消息状态，再增加编辑、重发和重新生成，明确本次使用的模型和上下文 |
| AI-G05 | 回答主要使用普通文本展示，代码、表格和长回答阅读体验有限 | 使用本地受限格式化展示，完善选择和明确的复制操作；不引入 WebView 或内容执行 |
| AI-G06 | 聊天、工具、设备代理和 Skill 管理分离；离开时清除临时内容的提示不一致。独立聊天当前也未提供键盘工作台的附件/页面入口 | 统一离开提示，设计用户明确选择内容的一次性任务交接；跨入口数据流需先确定范围 |
| AI-G07 | 角色和记忆主要手工维护，缺少从回答片段到记忆的便捷入口 | 增加“选择片段→审核编辑→指定角色保存”，保持主动保存和角色隔离 |
| AI-G08 | Skill 工具前置条件需要手工填写精确 ID，发现和纠错成本较高 | 增加可用能力目录、模板及缺失条件说明；继续保持纯声明文本，不执行脚本或自动授予工具 |
| AI-G09 | 压缩已有双重确认和撤销，但与上下文满额状态缺少联动 | 接近预算时提供入口，显示压缩前后差异及预计占用，保留原消息和撤销能力 |
| AI-G10 | 历史容量、保存失败和实际保留范围反馈不足；历史回答缺少便捷复用流程 | 增加容量反馈、管理入口和重新绑定后的显式复用；不将旧结果直接恢复为新编辑器的有效插入结果 |
| AI-G11 | 当前每个任务只支持一个远程连接，不能直接组合 Brave 与多个 MCP | 评估多连接时处理来源标识、工具重名、独立凭据、分别审核和逐次审批 |

关键依据：

- [AiFileTools.kt:11](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiFileTools.kt)、[AiToolModels.kt:43](../../ai-api/src/main/kotlin/dev/zeroinput/ai/api/AiToolModels.kt)：工具定义和请求没有文件名映射。
- [AiToolTaskActivity.kt:165](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiToolTaskActivity.kt)、[AiToolTaskView.kt:118](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiToolTaskView.kt)：文件标签留在本地任务展示，文件审批未就地展开映射。
- [AiConfigurationRepository.kt:33](../../user-data/src/main/kotlin/dev/zeroinput/userdata/AiConfigurationRepository.kt)：现有 Provider 和模型能力配置。
- [AiRequestEncoder.kt:29](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiRequestEncoder.kt)、[AiToolProtocol.kt:20](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiToolProtocol.kt)、[DeviceAgentProtocolCodec.kt:51](../../app/src/main/kotlin/dev/zeroinput/ime/ai/device/DeviceAgentProtocolCodec.kt)：不同任务的请求参数。
- [AiRemoteTools.kt:10](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiRemoteTools.kt)：一个任务一个已审核远程连接。

## 6. 尚未交付的能力与既定边界

| 领域 | 当前状态 | 评估结论 |
| --- | --- | --- |
| 页面 OCR | 有截图/OCR 相关抽象，生产链路尚未完整接入；当前页面引用主要读取一次性可见非编辑文字 | 不能视为已经支持页面截图识别；后续需单独明确许可、模型、数据流和负向验收 |
| 语音助手 | 系统助手主要提供文字会话；识别服务明确拒绝语音请求 | 尚无完整语音输入闭环，需明确产品文案及后续语音采集/处理方案 |
| 通用虚拟屏 | 仅验证固定自有 640×360 样例和普通 Android API | 第三方应用运行、输入注入及跨应用任务均未交付，且超出当前授权范围 |
| 跨应用设备代理 | 目标包或窗口变化时撤销任务 | 属于已确认安全设计；优化重点应先放在能力预检、阻断原因和恢复引导 |
| 设备阶段 6 | 已有分类别授权、单次操作、选定 URI、固定目标及逐次审批 | 功能存在，真实 OEM、旧 Android、硬件和系统授权表现仍需专项验收 |

实现定位：[PageScreenshotBroker.kt:19](../../app/src/main/kotlin/dev/zeroinput/ime/ai/page/PageScreenshotBroker.kt)、[AiDisabledRecognitionService.kt:8](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiDisabledRecognitionService.kt)、[AiVirtualScreen.kt:58](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiVirtualScreen.kt)。

上述扩展不能自动继承设备截图、页面引用、文件工具或其他能力的授权。后续方案继续遵守独立草稿、显式选择、最小数据、有界请求、前台绑定、逐次审批与代次撤销边界。

## 7. 建议推进顺序

| 阶段 | 建议范围 | 验收重点 |
| --- | --- | --- |
| 第一阶段：可靠性 | AI-B01 至 AI-B05；先解决上下文、草稿恢复和插入，再统一输出完整性 | 长对话、预算超限、失败队列、存储异常、提交拒绝、标签字面内容、长回答；成功和失效路径均覆盖 |
| 第二阶段：现有能力闭环 | 模型能力配置、多文件审批、上下文预算反馈、导航提示、回答展示、角色记忆入口和历史管理 | 用户能清楚知道发送什么、使用什么能力、失败如何恢复；各入口状态一致 |
| 第三阶段：任务协作与新能力 | 多 MCP、显式跨入口交接；再分别评估语音、OCR 等方向 | 每项扩展分别明确数据边界、必要授权、资源预算、取消条件及真实服务/设备验收 |

第一阶段主要在既有能力边界内修复失败处理；若实现过程中涉及持久化格式或新的跨入口数据流，仍需先明确对应方案。本报告不包含实现排期或未经验证的工期承诺。

## 8. 验证结果与限制

### 8.1 本轮分析时已执行的验证

```powershell
./gradlew.bat :ai-api:test :app:testDebugUnitTest `
  --tests 'dev.zeroinput.ime.ai.*' `
  --tests 'dev.zeroinput.ime.settings.Ai*' `
  :user-data:testDebugUnitTest --tests 'dev.zeroinput.userdata.Ai*' `
  :ime-ui:testDebugUnitTest --tests 'dev.zeroinput.ime.ui.Ai*' `
  privacyCheck '-Pandroid.injected.build.abi=x86_64' -PrequireRime=true
```

| 范围 | 测试数 | 通过 | 跳过 | 失败/错误 |
| --- | ---: | ---: | ---: | ---: |
| `ai-api` | 36 | 36 | 0 | 0 |
| 应用 AI 与相关设置 | 318 | 316 | 2 | 0 |
| AI 用户数据 | 34 | 34 | 0 | 0 |
| 相关 UI 单测 | 3 | 3 | 0 | 0 |
| 合计 | 391 | 389 | 2 | 0 |

`privacyCheck` 通过。两项跳过用例要求显式临时 Provider 凭据，本轮未提供。部分构建或依赖任务使用缓存或为 UP-TO-DATE；这不是全量测试结果，也不是平台验收结果。

另通过 JDK 21 JShell 调用当前编译产物，对上下文追加、队列失败和推理标签拆分做了定向逻辑复现，使用固定公开样本，不添加生产代码或测试源文件。

### 8.2 未验证项

- 本轮没有运行 Lint、重新打包或进行真机/模拟器验收。
- 没有调用真实模型、MCP、Brave，也没有执行真实短信、电话或硬件采集。
- AI-B03 的编辑器拒绝提交、AI-B05 的系统助手长回答仍缺少设备定向复现。
- 真实服务的参数兼容、网络时序和错误响应，以及 OEM/旧 Android 的授权、前台与系统助手行为仍需专项测试。
- 未进行覆盖全部第三方依赖的逐行安全审计，未测量长回答渲染性能；不将这些方向报告为已确认缺陷。

本次文档整理不重新执行实现测试，保留分析阶段的验证范围和结果；仅校验新增文档及进度入口的链接、路径和内容一致性。

## 9. 相关设计依据

- [项目架构](../architecture.md)、[威胁模型](../threat-model.md)、[权威进度](../project-progress.md)。
- [ADR 0015：AI 工作台边界](../adr/0015-ai-workbench-boundary.md)。
- [ADR 0019：显式页面引用](../adr/0019-explicit-page-references.md)。
- [ADR 0024：AI Provider 协议](../adr/0024-ai-provider-protocols.md)。
- [ADR 0025：设备代理边界](../adr/0025-ai-device-agent-boundary.md)、[ADR 0026：阶段 6 最小能力](../adr/0026-ai-device-agent-stage6-minimal-capabilities.md)。
- [ADR 0029：独立 AI 入口](../adr/0029-standalone-ai-entry.md)。
- [ADR 0030：角色与上下文库](../adr/0030-assistant-context-library.md)。
- [ADR 0031：声明式 Skill 与压缩](../adr/0031-declarative-skills-and-compaction.md)。
- [ADR 0032：工具任务与选定文件](../adr/0032-tool-tasks-and-selected-files.md)。
- [ADR 0033：显式远程工具](../adr/0033-explicit-remote-tools.md)。
- [ADR 0034：任务通知与系统助手](../adr/0034-task-notifications-and-native-assistant.md)。
- [ADR 0035：公开虚拟屏可行性](../adr/0035-public-virtual-screen-feasibility.md)。

<a id="phase-one"></a>

## 10. 第一阶段实施与复验（2026-11-20）

本轮范围为 AI-B01 至 AI-B05。第 5、6 节的功能建议继续作为后续评估项。尤其是跨入口传递聊天正文、OCR 和新增服务接入，需要各自确定数据范围和资源预算。

| 问题 | 用户现在可以做什么 | 验收依据 |
| --- | --- | --- |
| B01 上下文满额 | 保留已经勾选的内容；键盘和独立聊天的上下文按钮提示最新一轮未纳入。打开后可调整勾选或选择最近历史；独立聊天仍可审核压缩。 | 检查第 6、7、8 轮请求中的历史，以及参考材料和角色先耗尽字符预算的情形。 |
| B02 失败恢复 | 发送失败或主动停止后，逐条发送、取回编辑或移除。已完成回答若只在保存时失败，只列出尚未发送的问题。 | 复用原队列行；恢复上限为一个当前问题及原队列的四条待发内容。检查取消、已排程回调、手动恢复、满额和授权失效。退出、熄屏、切换模型或上下文会撤销临时内容。 |
| B03 编辑器拒绝插入 | 编辑器明确接受提交后关闭工作台。拒绝或无法确认时可检查正文，再在同一有效绑定内重试。原输入连接已更换时撤销结果。 | 模拟编辑器拒绝后再接受；核对第一次点击仍保留面板，第二次显式点击只提交一次，推理文字不进入正文。 |
| B04 代码中的推理标签 | Markdown 行内代码、围栏代码和块引用里的标签按字面保留。正常内嵌推理和协议提供的独立推理字段仍与可插入正文分开。 | 流式增量和完成事件使用同一解析器；测试逐字符分片、大小写、多个推理块和未闭合标签。 |
| B05 系统助手长回答 | 在原窗口滚动读到合法回答末尾。「打开新聊天」明确表示将开始新会话。 | 原生助手复用工作台的终态输出校验；检查 8,191、8,192、8,193 和 32,768 字符，以及窗口清除。 |

工程开销集中在既有队列、控件和文本解析路径。没有引入网络端点、第三方依赖、模型文件、持久化格式或权限。体积比较采用同一工作区、同一 ABI 和真实 Rime 的 Debug 构建，包含评估开始前已有的其他未提交改动。

### 10.1 验证结果

| 项目 | 结果 |
| --- | --- |
| 单元测试 | 根任务 `testDebugUnitTest` 及 `:ai-api:test` 对应报告合计 1,148 项，1,146 通过，两项真实 Provider 凭据用例跳过，失败和错误均为零。包含复用的构建结果。 |
| 隐私与静态检查 | `privacyCheck` 通过，`:app:lintDebug` 报告零问题。 |
| 平台验证 | Android API 37、x86_64、只读模拟器，13 项定向测试通过。包括拒绝一次提交后手动重试、已选回答分段、上下文与恢复按钮、存储失败、系统助手长回答和深浅主题横竖布局。模型回复与编辑器使用公开测试样本。 |
| 同配置包体 | ARM64、Debug、完整资源、真实 Rime。改动前 96,419,407 字节，重新生成的最终包 **96,426,255 字节（+6,848）**。符合 180,000,000 字节上限，仍高于 80 MB 目标。增量打包存在 ZIP 空隙，最终尺寸取重新生成的已签名 APK。 |
| 产物 | `apksigner verify` 通过；包中仅有 ARM64 原生库，含 `libzeroinput_rime.so`。SHA-256 为 `b0ea9724a88a437bcffa117b2f6e393b9f584373f47f339cce83902955268158`。 |

在仓库根目录复验构建和静态检查：

```powershell
./gradlew.bat :app:assembleDebug testDebugUnitTest :ai-api:test privacyCheck :app:lintDebug `
  '-Pandroid.injected.build.abi=arm64-v8a' -PrequireRime=true --max-workers=3
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest `
  '-Pandroid.injected.build.abi=x86_64' -PrequireRime=true --max-workers=3
```

平台测试范围为 `AiWorkbenchFlowTest`、`AiStorageFailurePanelTest`、`AiNativeAssistantPlatformTest` 及 `AiInteractionPanelTest#queueAndInterruptAreDistinctExplicitActionsAndEditingKeepsCurrentDraft`。本机保留构建日志 `.tmp-evidence/ai-phase1-verified.log`、重打包日志 `.tmp-evidence/ai-phase1-package.log` 和仪器测试结果 `.tmp-evidence/ai-phase1-platform.log`。

仍待验证的是自选服务实际鉴权失败与超时、第三方 OEM 编辑器提交语义，以及系统助手真实唤起与锁屏切换。本轮只生成 Debug 测试包。

<a id="phase-one-review"></a>

## 11. 第一阶段复查（2026-11-20）

复查覆盖 B01–B05 的请求历史、队列失败和恢复、输入绑定、回答解析、分段插入以及系统助手导航。公开样本在修改前触发了五个回归用例失败，分别覆盖终态裁剪、混合换行与缩进、超长列表、嵌套推理和伪结束围栏。另覆盖了未配对反引号和跨主线程回调的累计输出预算。

| 发现 | 已实施改进 | 检查方式 |
| --- | --- | --- |
| 完成事件会裁掉首行缩进与末尾换行，已选段落还会再次裁剪 | 完整回答保留原文。展示与插入使用同一组原文区间；部分选择保留源顺序、段内空白及所选段落的原有分隔符。 | 对照编辑器实际收到的字符串和下一请求中的历史；检查 CRLF、行尾空格、反序勾选及重复索引。 |
| 超过 128 段的列表会插入额外空行；普通阅读也会创建最多 128 个分段选项 | 超出上限的尾段覆盖连续原文。首次打开「选择段落」才创建选项，再次打开保留勾选，回答失效时清除。 | 接近 32,768 字符的标题列表只选尾段；在面板内检查长回答完成、打开、切换和撤销后的选项数。 |
| 嵌套推理可能提前结束；未配对反引号可能把推理当成代码；带后缀的伪结束围栏可能删掉正常代码 | 按嵌套深度识别推理。行内标签存在歧义时等待同段匹配的结束反引号；围栏结束行只接受标记和行尾空白。跨回调按完整响应累计字符预算。 | 检查逐字符分片、未闭合外层、空行边界、三种换行，以及主线程已消费过一批内容后继续超限的情况。 |
| 独立聊天仅剩恢复项或未选中的对话时，进入工具页可能直接丢掉临时内容 | 进入工具页前的离开提示也检查恢复项及对话记录，确认文案点明会清除待发内容。 | 核对前台导航判断和原队列的恢复、撤销测试。 |
| 系统助手打开新聊天失败时会提前清空原窗口；无展示用途的增量也逐条投递到主线程 | 拉起成功后再清除，失败时保留当前有效窗口并提示。仅将终态送到主线程。 | 审阅成功、异常和失效路径；平台验证范围见下文。 |

### 后续优先项

1. 多文件审批仍主要展示编号和参数 JSON。优先把用户已选文件的本地显示名与操作范围放在同一次确认中；未经选择的元数据不得进入模型请求。见 [AiToolTaskView](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiToolTaskView.kt)。
2. 工具和设备请求仍按协议提供固定输出预算。真实服务的参数接受情况、鉴权失败与超时反馈，还需使用明确指定的测试服务和临时凭据验证。见 [AiToolProtocol](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiToolProtocol.kt)。
3. 真实第三方编辑器返回拒绝或不确定结果、旧 Android 与厂商系统助手的生命周期表现，仍需代表性真机验收。当前措施是重新校验当前绑定并由用户显式重试。

### 复验结果

- B01 的历史超限提醒与选中内容、B03 的拒绝提交及重新绑定路径沿用第一阶段的测试，本次复验通过。上述新增用例随 `:ai-api:test testDebugUnitTest` 执行；对应 XML 合计 1,155 项，1,153 通过，2 项真实服务凭据用例跳过，无错误或失败。
- Android API 37 的只读 x86_64 模拟器通过 26 项仪器测试。另在独立系统会话中使用系统助理键唤起、输入固定公开草稿，验证熄屏唤醒后重新进入获得新草稿，并可打开独立聊天。将模拟器内的独立聊天组件临时禁用且确认目标不可解析后，点击「打开新聊天」可见失败提示，原草稿保留。组件已恢复，测试模拟器已退出。
- `privacyCheck` 通过，Debug Lint 零问题。ARM64 完整资源、真实 Rime 的 Debug APK 为 **96,427,631 字节**，较第 10 节的 96,426,255 字节增加 **1,376 字节**。APK 签名验证通过，仍达到 180 MB 上限要求并高于 80 MB 目标。SHA-256 为 `a34e36469fa47db1decd79f0542b41a82f4a640ea66b6c589deabd736ae9f181`。
- 本机证据包括 `.tmp-evidence/ai-recheck-red.log`、`.tmp-evidence/ai-recheck-final-x86.log`、`.tmp-evidence/ai-recheck-platform.log`、`.tmp-evidence/ai-recheck-final-arm64.log` 及 `.tmp-evidence/ai-recheck-arm64-debug.apk`。验证复用了部分构建缓存。分段优化核对了控件实际创建数，未测量响应耗时或帧率。

<a id="local-remaining"></a>

## 12. 剩余优先项的本机验收（2026-11-20）

### 文件审批与失败反馈

- 读取审批显示本次选择的文件编号与显示名、从零起算的 UTF-16 偏移、最多读取的长度和接收模型。搜索审批显示文件、搜索原文、大小写规则和接收模型，最多返回 10 个匹配片段。文件名和搜索词中的换行、方向控制符等在预览中转义。重新绑定界面时沿用原任务的映射，目标缺失时只能拒绝。三种协议的请求编码测试均检查本地文件标签未随工具定义发出。见[文件审批界面](../../app/src/main/kotlin/dev/zeroinput/ime/ai/AiToolTaskView.kt)及[平台用例](../../app/src/androidTest/kotlin/dev/zeroinput/ime/ai/AiFileToolsPlatformTest.kt)。
- 过去工具任务把鉴权、请求和超时错误统一显示为任务失败。现在界面按已有的错误类别提示，传递给界面的只有错误类型。使用模拟 HTTP 401 和超时异常验证传输映射，另在任务层检查分类保留、终态及请求次数。见[传输测试](../../app/src/test/kotlin/dev/zeroinput/ime/ai/AiToolTransportTest.kt)及[任务测试](../../app/src/test/kotlin/dev/zeroinput/ime/ai/AiToolRunTest.kt)。

### 本地编码核对

| 协议 | 工具任务的输出预算 | 设备代理的输出预算 |
| --- | --- | --- |
| Chat Completions | `max_tokens=4096` | 省略 |
| Responses | `max_output_tokens=4096` | `max_output_tokens=2048` |
| Anthropic Messages | `max_tokens=4096` | `max_tokens=2048` |

以上是两条路径实际生成的请求字段。设备代理的 Chat 路径是第 11 节概括中的例外。检查覆盖字段名、数值与应省略的字段，真实模型能否接受这些参数仍需按服务和模型单独验证。见[工具协议测试](../../app/src/test/kotlin/dev/zeroinput/ime/ai/AiToolProtocolTest.kt)和[设备协议测试](../../app/src/test/kotlin/dev/zeroinput/ime/ai/device/DeviceAgentProtocolCodecTest.kt)。

### 旧系统和编辑器

- Android 10（API 29）创建键盘时触发 `NoSuchMethodError`。组合编辑和私密草稿视图在该系统会调用 API 30 才提供的 `setImportantForContentCapture`。已修正两处版本判断，原复现路径可正常创建键盘。见[组合编辑视图](../../ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/CompositionEditorView.kt)和[私密草稿视图](../../ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/PrivateDraftPanelView.kt)。
- 在模拟器上用可控编辑器覆盖了明确拒绝、已写入却返回失败及切换输入框的情况。旧结果不会自动重发到原输入框或新输入框。另校正一条旧平台断言，分别验证成对标点已提交、光标移动抛错、写入次数为一，以及重新转换上下文失效。见[工作台流程测试](../../app/src/androidTest/kotlin/dev/zeroinput/ime/ai/AiWorkbenchFlowTest.kt)和[编辑器异常测试](../../app/src/androidTest/kotlin/dev/zeroinput/ime/input/AndroidEditorConnectionFailureTest.kt)。

### 验收记录

- `testDebugUnitTest :ai-api:test` 对应 XML 合计 1,157 项，1,155 项通过，2 项真实服务测试因缺凭据跳过。部分构建结果来自缓存。`privacyCheck` 通过。
- 独立 Android 10 x86_64 模拟器通过 26 项定向仪器测试，覆盖文件工具、原生系统助手、键盘工作台和编辑器异常。构建日志为 `.tmp-evidence/ai-files-verified-x86.log`，最终平台结果为 `.tmp-evidence/ai-files-final-api29.log`。
- 另用系统助手键实际唤起 Android 10 助手，输入公开样本，确认系统熄屏后再唤醒。再次进入时显示空草稿，点击「打开新聊天」进入独立聊天的空输入框。在模拟器内临时禁用目标组件后，点击同一按钮会显示打开失败，当前草稿保留。已恢复组件并确认可解析。该模拟器使用普通锁屏，未测试 PIN 或生物识别解锁。
- 同配置 ARM64 完整资源、真实 Rime 的签名 Debug APK 为 **96,431,659 字节**，较第 11 节增加 **4,028 字节**。低于 180,000,000 字节验收上限，仍高于 80,000,000 字节目标。Debug Lint 零问题，`apksigner verify` 通过，包内仅含 ARM64 原生库。本轮未引入新依赖。产物为 `.tmp-evidence/ai-files-arm64-debug.apk`，SHA-256 为 `2e4cdaea5ab1df297e8779e0a55ab53ecc2eb5676d5d76ece143d47e18fe0984`。
- 仍需外部资源验收的项目是指定真实服务与模型的参数接受情况、实际鉴权和超时反馈，以及厂商实体设备上的编辑器与系统助手行为。其余 G02–G09 中的产品扩展建议保持原评估状态。
