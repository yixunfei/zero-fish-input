# AI 设备代理设计与实现计划

日期：2026-10-21
状态：阶段 0–6 核心实现已完成；页面引用与设备代理专项 instrumentation（7/7）、单元测试、privacyCheck、Lint、x86_64 Debug 构建及 `-PrequireRime=true` Release 构建通过。完整 Android instrumentation 392 项中 8 项失败、14 项按环境/真实服务条件跳过；5 项键盘尺寸/布局断言稳定失败，3 项整套运行时的 fixture/启动时序失败但单独重跑通过。Activity 实例重建及进程终止后从最近任务卡片恢复已在 Android 16 模拟器验证。物理设备兼容性、阶段 6 实机权限/硬件行为和真实模型服务仍待验收。
范围：ZeroInput 内独立的 AI 设备操作模式

## 实施状态（2026-10-21）

用户已确认侧载/自有分发，并同意按本计划推进阶段 0–5；随后确认阶段 6 的直接读取/操作范围。
截图按任务单独确认但
默认关闭；代理历史默认不保存。当前代码已形成可用闭环：`ai-api` 契约与固定动作
目录、Android 无障碍观察与动作分发、三种 provider tool-call、策略/代次撤销、设置页
和 AI 面板独立设备任务模式均已落地。阶段 6 直接能力按 ADR 0026 实现，默认关闭且每次操作需单独审批。

阶段 0 的 ADR、架构和安全边界已同步；阶段 1、3、4 的平台无关契约、策略、协议和
有界代理循环已通过当前单元测试。阶段 2 的结构化前台节点快照与 Android 30+ 有界截图
适配器已落地，公开 fixture 的快照、截图、敏感字段和撤销 instrumentation 已在 Android 16 模拟器通过。阶段 5 已提供 AI 面板模式和独立
`AiDeviceAgentActivity`，包含独立草稿、开始/取消、授权/停止状态条、动作审批和内存时间线；
Activity 实例重建只重新绑定协调器，不重复启动任务；Coordinator 单元测试和 Activity 实例重建 instrumentation 已分别覆盖该行为。任务、审批、动作结果和选中文件只在进程内存中，未加入持久化；任务与草稿视图禁用状态保存。Android 16 模拟器使用合成草稿验证了进程终止后从最近任务卡片恢复同一系统任务：旧进程 PID 消失，点击最近任务卡片启动新进程并恢复任务页，草稿、目标包、附件状态和时间线均为空，停止操作不可用，也没有恢复代理任务或旧审批。系统低内存终止和物理设备恢复仍需单独验收。

截图设置默认关闭。设置页是能力总开关，独立设备任务页还要求每次任务确认；API 30+ 在任务
代次、目标窗口和解锁状态有效时捕获一帧有界 JPEG，并以三种协议支持的图片内容发送。截图
不落盘，快照或任务失效时清零；不支持、超限或过期时 fail closed。

## 1. 决策摘要

ZeroInput 将增加一个与普通 AI 文字工作台隔离的“设备代理”模式。设备代理默认关闭，只有用户从设置或 AI 面板主动开启、阅读应用内声明并确认后，才允许进入 Android 的辅助功能授权页面。系统授权返回后仍需要用户再次启动任务；授权本身不会触发读取屏幕或执行操作。

本功能参考 OpenDroid 的设备内 `AccessibilityService`、结构化节点查找、手势分发、动作目录和代理循环设计，但不复制其权限清单、自动批准策略或业务动作。参考基线为 OpenDroid `54be0782855fc3cbabe615ea2e097280355efe0c`（2026-10-18），其代码许可和第三方声明在实际引入任何代码前仍需单独审计。当前设计只借鉴行为和边界，不直接复制源码。

设备代理由两个相互独立的设置轴组成：

1. **能力范围**：`受限白名单` 或 `完整支持范围`。白名单按精确包名和动作类别匹配；完整范围表示本应用支持的动作目录，不表示 root、ADB、Shizuku 或绕过 Android 系统安全限制。
2. **审批策略**：`每步审批`、`任务审批`、`仅安全动作自动执行`。高影响和不可逆动作始终需要单独的最终确认；任何策略都不能绕过敏感输入、锁屏、权限对话框、支付和系统凭据等硬阻断。

首次实现的完整代理循环只在设备解锁、代理运行状态条可见且目标应用明确时运行。目标应用仍在前台时，审批和停止控件通过任务期间的 `TYPE_ACCESSIBILITY_OVERLAY` 显示，不使用 `SYSTEM_ALERT_WINDOW`，不在空闲时常驻，也不通过浮层读取或点击底层内容。锁屏、辅助功能服务断开、前台包名变化、输入框或窗口变化、设置变化和任务取消都会暂停或撤销待执行动作。后台常驻、自主通知回复、剪贴板、短信/电话、联系人、日历、支付、应用安装和文件管理不随第一阶段自动开放；这些能力保留在动作目录的隔离扩展点中，必须经过单独的权限、威胁模型和负向测试评审。

## 2. 目标与非目标

### 2.1 目标

- 在用户明确启动的任务中，让模型读取当前前台应用的受限 UI 快照，并通过固定工具目录执行点击、滚动、滑动、输入和导航。
- 让用户在任务开始前决定目标应用、能力范围和审批策略，并在动作执行前看见可理解的动作描述。
- 支持 OpenAI Chat Completions、Responses 和 Anthropic Messages 三种现有协议的受控 tool call 循环；普通文字工作台继续保持纯文本响应契约。
- 让所有 UI 读取、模型请求、动作执行和结果回传都绑定任务代次、目标包名、窗口标识和 UI 快照版本。
- 让 AccessibilityService 的用途、读取内容、发送内容、执行动作和撤销方式在应用内显著披露，满足非残障用途的用户同意要求。
- 保持普通输入、用户词库、emoji、安全剪贴板和现有 AI 文字模式的离线与隐私边界。

### 2.2 非目标

- 不把 ZeroInput 宣称为 Android 无障碍工具，也不设置 `isAccessibilityTool=true` 来规避政策。当前服务应保持 `isAccessibilityTool=false`，并按自动化应用要求展示声明和取得明确同意。
- 不提供 root、ADB、Shizuku、系统签名权限、隐藏 API、反射或任意 shell 执行。
- 不让模型直接调用 Android API、类名、反射方法或动态脚本。模型只能选择静态动作目录中的工具。
- 不自动读取系统剪贴板、私有安全剪贴板、通知、通话记录、短信、联系人、日历、文件、位置、相机或麦克风。
- 不在代理任务中自动读取当前编辑器文本、选择区或密码字段；键盘输入和设备代理的数据源保持分离。
- 不在后台持续观察屏幕；任务只在用户启动的任务代次有效且目标应用仍处于前台时推进。设备代理 Activity 可以在打开目标应用后暂时位于后台，但切换离开目标应用会立即撤销任务；不自动重新申请已撤销的权限。
- 不把 OpenDroid 的 YOLO 语义、浮动按钮、自动回复和第三方业务动作直接移植进 ZeroInput。

## 3. 参考项目评估

### 3.1 OpenDroid 可借鉴部分

参考 https://github.com/yashab-cyber/opendroid/ 项目

- `OpenDroidAccessibilityService` 持有 Android 平台句柄，提供节点查找、文本设置、滚动和 `dispatchGesture`。
- `GenericAppAutomator` 把动作封装为小接口，并对冷启动 UI 做有界重试。
- `ActionSchema` 将名称、参数、类别、风险和是否允许自动批准集中管理。
- `ActionSequenceExecutor` 统一处理顺序执行、参数引用、失败和单级回退。
- `AgentLoop` 将计划、动作执行、用户补充输入和重新评估串成有限循环。
- OpenDroid 的 instrumentation tests 展示了用固定公开页面测试节点遍历和点击定位的方式。

### 3.2 不直接采用的部分

- 它的 `YOLO` 自动批准模式会让用户一次性放弃动作审批，不符合 ZeroInput 的高风险默认策略。
- 它把屏幕截图、对话历史和代理循环放在通用设备助理中；ZeroInput 必须按任务、包名、窗口和代次隔离，并限制发送给模型的内容。
- 它的动作目录包含发消息、打电话、支付、下单、文件操作、应用安装等外部副作用。ZeroInput 只能在以后为每类能力建立单独权限和确认边界后再考虑。
- OpenDroid 的无障碍服务是操作型服务；当前 ZeroInput 的 `PageReferenceService` 是只读的一次性页面引用服务，不能将其改造成动作服务。

## 4. 产品状态与用户流程

### 4.1 独立状态机

```text
OFF
  -> DISCLOSURE_REQUIRED
  -> SYSTEM_AUTHORIZATION_PENDING
  -> READY
  -> TASK_CONFIGURING
  -> OBSERVING
  -> PLAN_REVIEW
  -> ACTION_APPROVAL
  -> EXECUTING
  -> PAUSED / COMPLETED / FAILED / REVOKED
```

状态转换规则：

- `OFF` 不创建代理服务请求，不访问 Accessibility 节点，不生成设备工具请求。
- `READY` 只表示设置开关和系统授权均有效，不表示模型可以自动运行。
- `TASK_CONFIGURING` 必须绑定一个当前前台包名或用户明确选择的已安装包名；不能使用应用显示名称作为唯一身份。
- `OBSERVING` 只在显式用户动作或当前代理循环需要下一步决策时读取一次快照。
- `PLAN_REVIEW` 展示模型计划的动作类别、目标应用、输入文本和预计副作用；用户确认后才能进入执行。
- `ACTION_APPROVAL` 按审批策略决定显示每步、整项或自动执行。高风险动作不能被批量确认隐藏。
- `EXECUTING` 每次平台调用前后都校验任务代次、服务连接、前台包名、窗口 ID、锁屏状态和快照版本。
- `PAUSED` 只保留不含屏幕正文的任务状态和待用户决定的原因；恢复前必须重新观察，不复用旧节点对象。
- `REVOKED` 清除工具结果、截图、节点文本、输入草稿和待执行动作；迟到的网络或平台回调静默丢弃。

### 4.2 首次开启声明弹窗

开启设备代理或重新请求系统授权前，应用内必须显示独立的显著声明，不与普通隐私政策合并。建议文案结构如下：

> **设备代理需要辅助功能权限**
>
> ZeroInput 只会在你主动启动设备代理任务、设备已解锁且任务状态条处于活动状态时，读取当前应用公开的界面元素或你明确请求的屏幕画面。AI 可能根据这些内容提出点击、滑动、输入和导航操作。你可以选择受限应用白名单、逐步审批或完整支持范围。
>
> 选定的界面文字、屏幕图像、任务内容和动作结果可能发送到你配置的 AI 服务。ZeroInput 不会通过此功能读取系统剪贴板、ZeroInput 私有剪贴板、密码字段、通知、短信、联系人或后台页面。辅助功能授权较宽，应用本身无法保证其他应用正确标记所有敏感内容；你应在执行前检查目标应用和动作。
>
> 你可以随时停止任务、关闭设备代理或在系统设置中撤销权限。撤销后，待执行动作和未消费的代理结果立即失效。

弹窗必须提供：

- “我已阅读并理解设备代理会读取界面并执行我确认的操作”复选框。
- “查看能力与权限”入口，列出当前能力范围和拟申请的系统权限。
- `取消`、`继续到系统设置` 两个明确按钮；没有同意不能进入系统授权页。
- 返回应用后显示授权状态和测试结果；返回不自动开始任务。
- 每次权限被撤销后重新请求都再次显示用途和限制说明。

### 4.3 任务启动

1. 用户从 AI 键盘面板或 ZeroInput 设置进入设备代理入口。
2. 用户开启设备代理开关；如果尚未同意声明，先完成声明；如果尚未授权系统服务，再打开系统设置。
3. 用户选择能力范围、审批策略、目标应用（精确包名）和是否允许截图作为模型观察结果。
4. 用户输入任务。设备代理使用独立草稿，不读取外部编辑器内容。
5. 用户点击“开始任务”。代理先展示当前前台应用、目标包名、服务状态和数据发送提示；不满足条件时停在配置页。
6. 模型只能获得用户任务和当前请求所需的最小观察结果。每个工具调用均进入本地策略与审批管线。
7. 任务结束后只显示结果和动作摘要；插入外部编辑器仍需用户明确点击，并重新校验原有 IME 会话。

## 5. 能力、权限与审批模型

### 5.1 能力范围

| 范围 | 规则 | 默认值 |
| --- | --- | --- |
| 关闭 | 不读屏、不发工具请求、不执行动作 | 是 |
| 受限应用白名单 | 精确包名 + 精确动作类别；未匹配应用即暂停 | 否 |
| 完整支持范围 | 允许当前版本动作目录中的全部低风险和可逆动作；仍受硬阻断和高风险确认约束 | 否 |

白名单条目只保存精确包名、允许的动作类别、创建时间和启用状态。显示名称、图标和版本只能作为界面信息，不能作为授权身份。系统包、ZeroInput 自身、锁屏和权限设置页默认不在白名单内。

### 5.2 审批策略

| 策略 | 行为 | 适用场景 |
| --- | --- | --- |
| 每步审批 | 每个动作显示目标、动作、参数和预期结果；用户逐步批准或拒绝 | 默认、敏感任务 |
| 任务审批 | 用户确认完整计划后，代理可执行计划中的低风险动作；计划改变或目标变化则重新审批 | 有限白名单任务 |
| 仅安全动作自动执行 | 只自动执行观察、等待、返回、滚动、显式目标的可逆点击；输入、外部跳转和高风险动作仍需批准 | 熟练用户 |

“完整支持范围”不是“无条件自动化”。下列硬阻断不受审批策略覆盖：

- 锁屏、设备凭据、密码/PIN、可见密码和标记为敏感的节点。
- 系统权限授予、默认应用更换、无障碍设置本身、VPN/网络安全设置和开发者选项。
- 银行、支付、订单确认、转账、购买、删除账户或不可逆删除。
- 短信、电话、邮件、社交发布、通知回复和联系人操作，除非未来为该类别单独完成权限与威胁评审。
- 复制、读取或写入系统剪贴板及 ZeroInput 私有剪贴板。
- 任意安装、卸载、更新 APK，文件读写、压缩、执行和共享。
- 目标包名、窗口或 UI 快照在动作产生后发生变化。

### 5.3 Android 权限分层

第一阶段只申请和启用下列能力：

- `BIND_ACCESSIBILITY_SERVICE`：读取当前可见的 UI 节点、执行用户批准的节点动作和手势。服务单独实现，不复用 `PageReferenceService`。
- `INTERNET`：沿用现有用户批准的 AI 网络例外；只允许 `OpenAiCompatibleProvider` 传输设备代理请求。
- Android 30+ 的 `AccessibilityService.takeScreenshot`：仅在用户开启“允许屏幕观察”、代理任务活跃且当前页面通过策略检查时使用。截图只在内存中短暂存在，未经用户确认不得作为工具结果发送。

任务运行时的审批和停止控件使用 `TYPE_ACCESSIBILITY_OVERLAY` 显示最小的浮层状态条/审批面板，使目标应用仍可见。它只在用户启动的任务期间存在，不使用 `SYSTEM_ALERT_WINDOW`，不抢输入焦点，也不把底层应用内容交给浮层。该浮层会扩大辅助功能服务的可见交互面，必须纳入首启声明、隐私扫描和设备测试。

后续能力需要独立开关、声明和评审：

- 屏幕录制：使用 `MediaProjection` 用户确认和符合系统版本要求的前台服务；默认不启用。
- 通知访问：只有明确的通知阅读产品需求才评审 `BIND_NOTIFICATION_LISTENER_SERVICE`，不能作为设备代理的隐含能力。
- 通话、短信、联系人、日历、相机、麦克风、位置、外部存储和悬浮窗：不进入第一阶段；每项都必须新增 ADR、权限白名单、数据最小化和负向测试。

明确排除：`QUERY_ALL_PACKAGES`、`PACKAGE_USAGE_STATS`、`SYSTEM_ALERT_WINDOW`（设备代理不使用悬浮球）、Shizuku、root、ADB、系统签名权限、后台录屏和任意 shell。

## 6. 系统与模块设计

### 6.1 依赖方向

```text
app -> ai-api -> (device-agent contracts only)
app -> ime-core / ime-ui / user-data / security
app -> OpenAiCompatibleProvider (sole network adapter)
DeviceAgentAccessibilityService -> DeviceAgentBroker -> DeviceAgentCoordinator
DeviceAgentCoordinator -> DeviceActionPolicy -> DeviceActionDispatcher
DeviceActionDispatcher -> DeviceAgentAccessibilityAdapter
```

设备代理不得让 `ime-core`、`ime-ui` 或 `ai-api` 依赖 Android `AccessibilityNodeInfo`、`AccessibilityService`、`Bitmap` 或 Rime 类型。平台对象只存在于 `app/ai/device` 内，经过不可变 DTO 传给代理控制器。

### 6.2 建议新增契约

在 `ai-api` 中增加平台无关的最小接口：

- `DeviceAgentRequest`：任务 ID、用户任务、目标包名、能力范围、审批策略、上下文代次和可用工具。
- `DeviceActionDefinition`：工具名称、参数 schema、类别、风险、是否需要确认、允许的目标范围。
- `DeviceActionCall`：模型提出的调用、调用 ID、快照 ID、参数和模型轮次。
- `DeviceActionResult`：成功/拒绝/过期/失败、脱敏摘要、下一步可用性；不能把异常正文或节点对象暴露给模型。
- `DeviceScreenSnapshot`：前台包名、窗口 ID、快照 ID、结构化可交互元素、可选截图引用、截断标记和时间戳。
- `DeviceAgentEvent`：任务开始、观察、计划、审批请求、动作开始、动作结果、暂停、完成、失败和取消。
- `DeviceAgentPolicy`：能力范围、审批策略、包名白名单、截图开关和单任务限制。

这些模型必须不可变、有长度/数量限制，并用 `toString` 脱敏。工具参数不能使用任意 JSON 透传；每个参数都由定义和本地校验器解析。

### 6.3 Android 适配器

新增独立的 `DeviceAgentAccessibilityService`，不要把操作能力加到现有 `PageReferenceService`。服务职责：

- 只在 `DeviceAgentBroker` 已安装当前任务且代次匹配时响应显式命令。
- 提供节点树快照、元素定位、点击、长按、滚动、有限手势、非敏感输入和 IME action。
- 不在 `onAccessibilityEvent` 中读取 `event.text`、`event.source` 或根节点；事件只用于标记任务过期。
- API 33+ 禁用节点缓存；所有节点和窗口在使用后回收，不跨调用持有。
- 节点遍历、文本、深度、窗口数、坐标、截图尺寸和执行时长全部有界；超限返回“观察不完整”，不隐含重试到无界。
- 通过 `KeyguardManager`、窗口类型、包名、节点属性和平台敏感标志拒绝锁屏、输入密码、不可见或敏感节点。

`PageReferenceService` 继续保持只读的一次性页面引用职责，privacyCheck 不应放宽为允许它执行操作。

### 6.4 代理协调器和动作分发

建议 `app/ai/device` 内部职责如下：

- `DeviceAgentCoordinator`：任务生命周期、代次、模型轮次、取消、前后台和结果交付。
- `DeviceAgentPolicyEvaluator`：能力范围、包名白名单、审批状态、硬阻断和数据发送判断。
- `DeviceActionCatalog`：静态动作定义和 schema；任何新动作必须先加入目录并配套测试。
- `DeviceActionDispatcher`：解析参数、绑定快照、执行一次动作、校验前后状态和生成脱敏结果。
- `DeviceApprovalController`：将模型计划转换成用户可理解的审批请求；审批结果一次性消费并绑定调用 ID。
- `DeviceAgentAccessibilityBroker`：在服务连接和任务代次之间交接不可变命令；不保留 `Activity`、`View`、`InputConnection` 或节点对象。
- `DeviceAgentRedactor`：在进入网络、历史、界面和错误文本前移除节点正文、密钥、包内个人数据和坐标之外的敏感元数据。
- `DeviceAgentLimits`：集中保存轮次、动作数、时间、树大小、截图大小、工具结果和文本输入限制。

### 6.5 AI tool-call 传输

普通 AI 文字模式当前明确拒绝 `tool_calls`，这一行为必须保留。设备代理新增独立的 `AiAgentProvider`/`AiToolLoop` 契约，仍复用现有唯一 HTTPS I/O 适配器，但使用独立的请求编码和响应解析：

1. Chat Completions：支持受限 `tools`、`tool_calls` 和 `tool` 消息。
2. Responses：支持受限 function tool call 和 tool output。
3. Anthropic Messages：支持 `tools`、`tool_use` 和 `tool_result`。

协议层只接受静态工具定义、合法调用 ID、合法 JSON 参数和明确的停止/继续状态。模型不能新增工具、修改动作风险、修改包名白名单、读取凭据或直接发送任意 HTTP 请求。

建议初始上限：每任务最多 24 个模型轮次、48 个动作、5 分钟墙钟时间；单次树快照最多 512 个节点/32 层/32,768 个 UTF-16 单位，截图最多 2 MiB，单次工具结果最多 32 KiB。达到上限时进入暂停并要求用户重新开始，不自动续跑。

### 6.6 代理循环

```text
用户任务
  -> 任务策略快照
  -> 显式观察（UI 树 / 用户允许的截图）
  -> 模型 tool call
  -> 本地参数与目标校验
  -> 审批（必要时）
  -> Accessibility 动作
  -> 前后状态与代次校验
  -> 脱敏 tool result
  -> 下一轮或完成
```

每轮必须保存不可变 `TaskToken(sessionGeneration, taskId, packageName, windowId, snapshotId)`。任何一项不匹配都返回过期并停止循环；不能把旧的元素引用解析到新页面，也不能把旧动作结果发送给新任务。

## 7. 数据与隐私边界

### 7.1 可以进入模型请求的数据

- 用户在设备代理独立草稿中输入的任务。
- 用户明确允许的当前前台 UI 结构化元素和/或一次截图。
- 当前任务产生的脱敏动作结果，例如“点击成功”“页面未找到目标”。
- 用户明确选择的、现有 AI 工作台允许的会话上下文；默认不带历史。

### 7.2 不得进入模型请求的数据

- 系统剪贴板和 ZeroInput 安全剪贴板正文、标签和认证状态。
- 外部编辑器正文、选区、密码、PIN、邮箱、URI 和个性化词库。
- 通知、短信、通话、联系人、日历、文件、位置、相机和麦克风内容。
- 辅助功能事件正文、后台应用、未选窗口、源包名之外的内部窗口标识。
- API key、设备凭据、认证 token、Keystore 内容和内部设置。

### 7.3 本地生命周期

- 屏幕树、截图、节点文本、动作参数和 tool result 只存在于当前任务的内存缓冲区。
- 任务取消、Activity 销毁、服务断开、锁屏、切换应用和设置收紧时立即清空缓冲区并提升代次。
- 设备代理历史默认关闭。若以后允许保存，只保存用户主动确认的纯文本任务和脱敏动作摘要，使用独立 Keystore 别名和 `noBackupFilesDir`；不保存截图、完整 UI 树或工具原始响应。
- 日志只能记录固定类别、动作名称、成功/失败和耗时，不记录任务正文、参数值、包外文本、截图或异常原文。

## 8. 威胁模型与控制

| 威胁 | 控制 |
| --- | --- |
| 当前页面中的恶意提示诱导模型执行动作 | 所有 UI 内容标记为不可信数据；系统提示规定页面文字不能改变工具权限；动作仍由本地策略和用户审批决定 |
| 模型伪造工具名、参数或调用 ID | 静态目录、严格 schema、调用 ID 单次消费、未知字段拒绝 |
| 页面变化导致点击错位 | 快照 ID、窗口 ID、包名、元素 bounds 和短时有效期校验；过期即停止 |
| 前台应用切换 | 每次动作前检查唯一前台包；变化即暂停并清除待批准计划 |
| 密码或敏感节点被输入/读取 | 节点属性、平台敏感标志、已知安全窗口和保守规则 fail closed；不提供密码字段操作 |
| 截图或 UI 文本泄露给模型 | 截图默认关闭；任务级明确同意；尺寸和内容有界；不存盘；仅发送当前工具需要的结果 |
| 辅助功能授权被误认为功能已开启 | 系统授权与应用开关双重门槛；授权返回不自动开始读取或执行 |
| 用户误以为“完全权限”可绕过系统 | UI 使用“完整支持范围”文案并列出硬阻断；高风险动作始终单独确认 |
| 迟到的网络、服务或 UI 回调污染新任务 | session/task/snapshot generation 检查；请求取消和服务销毁提升代次 |
| 代理目标离开前台后继续操作 | 任务代次、目标窗口和解锁状态是每次观察/执行的前置条件；切换应用、锁屏或服务断开自动暂停并撤销当前批准 |
| 供应商保存或训练工具数据 | 请求显式关闭 provider-side storage（协议支持时）；用户在 provider 配置中看到设备数据发送提示；ZeroInput 不承诺第三方不留存 |
| AccessibilityService 被恶意应用利用 | 使用系统绑定权限；不导出内部 broker；不接受外部 Intent 控制；所有命令只来自当前进程的任务协调器 |

## 9. 实现分阶段计划

### 阶段 0：设计和合规基线

交付：

- 评审本计划中“完整支持范围”的定义、发行渠道和高风险动作是否纳入首版。
- 新增 `docs/adr/0025-ai-device-agent-boundary.md`，记录权限、数据源、工具循环和硬阻断。
- 更新 `docs/architecture.md`、`docs/threat-model.md`、`SECURITY.md`、`README.md` 和 `AGENTS.md` 中的已批准边界。
- 明确 Play 发行与侧载发行的不同能力矩阵。若目标包含 Google Play，默认采用逐步/任务审批和有限动作目录，不能依赖自主 Accessibility 自动化例外。
- 在 `privacyCheck` 中加入新的服务、服务 XML 能力、权限白名单和源代码访问边界；保持 `PageReferenceService` 只读规则。

退出条件：ADR、威胁模型、权限矩阵和用户声明文案评审通过。

### 阶段 1：平台无关契约与策略

交付：

- `ai-api` 的设备代理请求、快照、动作目录、审批、事件和结果模型。
- 严格限制、脱敏 `toString`、schema 校验和工具调用解析。
- `DeviceAgentPolicyEvaluator` 纯 Kotlin 单元测试：关闭、白名单、完整范围、审批策略、风险分级、硬阻断和代次失效。
- 确定不改变现有 `AiRequest`、会话格式和普通文本响应行为。

退出条件：没有 Android 依赖的契约测试通过，普通 AI tool call 仍被拒绝。

### 阶段 2：只读观察服务

当前状态：结构化前台节点观察、窗口/服务失效、有界快照和 API 30+ 有界截图已实现。公开
fixture Activity 的快照、敏感字段脱敏、截图 JPEG 边界、审批后点击、前台切换、锁屏和服务撤销
instrumentation 已在 Android 16 模拟器通过；物理设备兼容性仍需单独记录。

交付：

- 独立 `DeviceAgentAccessibilityService` 和 `DeviceAgentAccessibilityBroker`。
- 当前前台窗口、节点树、元素 bounds、焦点和敏感属性的有限快照。
- 可选截图适配器，任务级确认后才发送模型；截图默认关闭并在内存中短暂存在。
- 服务连接、授权撤销、锁屏、前台包变化和窗口事件的代次失效。
- 公开固定文字 fixture Activity 和 instrumentation tests。

退出条件：禁用或未授权时零节点读取；超限、敏感节点、锁屏和窗口不确定性均 fail closed；`privacyCheck`、合并 Manifest 检查和 Lint 通过。

### 阶段 3：可逆 UI 动作与审批

交付：

- 观察、等待、返回、主页、滚动、点击、长按、滑动、打开指定应用和非敏感字段输入等低风险动作。
- 每个动作的前置条件、快照绑定、超时、失败分类和前后状态检查。
- 每步审批、任务审批、仅安全动作自动执行的 UI 和持久化设置。
- 目标包名白名单和应用切换暂停。
- 不实现剪贴板、通知、短信、电话、支付、文件、安装/卸载和权限授予。

退出条件：公开 fixture 能稳定完成成功、拒绝、错位、取消、锁屏和应用切换用例；真实第三方应用只做手工兼容性记录，不作为自动化隐私证明。

### 阶段 4：受控 tool-call 代理循环

交付：

- `AiAgentProvider` 与三种已支持协议的 tool-call encoder/reader。
- 仍只使用 `OpenAiCompatibleProvider` 的 HTTPS I/O；无新网络库、WebView、远程 MCP 或自动 fallback。
- 有界模型轮次、动作数、任务时间、工具结果和截图预算。
- tool call 与本地动作的审批、重试、暂停、恢复和取消。
- prompt injection 负向测试：页面文字不能创建工具、改变策略、泄露密钥或批准动作。

退出条件：正常 AI 文本路径无回归；模型未知工具、非法参数、重复调用、乱序结果、超限和 provider 中断均安全失败。

### 阶段 5：独立设备代理 Activity 和设置

当前状态：设置页、AI 面板独立设备任务模式和独立 `AiDeviceAgentActivity` 已实现，包含独立
草稿、开始/取消、停止状态条、动作审批和内存时间线；Activity 实例重建只重新绑定现有任务。
Android 16 模拟器已通过撤权、锁屏、前台切换、公开 fixture 观察/点击和 Activity 实例重建专项测试（页面引用与设备代理专项共 7/7）。
Android 16 模拟器已验证 Activity 进程被杀后点击最近任务卡片可恢复系统保留的 task record；物理设备和真实模型服务仍需单独验收。

交付：

- 独立 `AiDeviceAgentActivity`/面板，不复用普通 AI 结果插入视图作为执行控制器。
- 首次声明、权限状态、能力范围、包名白名单、审批策略、截图开关、当前目标和停止按钮。
- 任务摘要、动作审批、执行时间线、暂停原因、完成结果和错误空状态。
- 用户返回输入框时，结果插入仍走现有 session/input-connection 校验；代理不持有旧连接。
- 中英文资源、无障碍描述、紧凑屏幕、横竖屏和大字体布局。

退出条件：设备解锁、权限撤销、Activity 重建、旋转、目标应用切换和返回操作不产生重复动作或旧结果插入；Activity 暂时位于后台不改变目标前台约束。

### 阶段 6：高风险直接能力（已实现，默认关闭）

用户要求通知读取及通知相关行为、用户指定媒体/系统设置、相机/麦克风、文件选择、短信/电话，以及用户指定联系人/日历的直接读取或操作。实现采用独立能力开关、任务目标绑定、一次性审批、短期 grant 和撤销检查；阶段 0–5 的进程终止后最近任务卡片恢复已在模拟器验证，物理设备和真实 provider 验收仍未完成。

共同门槛：每种能力必须有独立 ADR、权限/Play 声明分析、单独能力开关、显著首次披露、明确的数据输入/输出与本地留存规则、不可自动批准动作表、最终确认界面、负向/撤销/清除测试和设备兼容性报告。不能通过设备代理总开关一次性打开多个类别。

| 类别 | 建议最小访问边界 | 主要风险与当前状态 |
| --- | --- | --- |
| 文件/媒体选择 | 系统选择器仅授予用户点选的 URI；限制 MIME、大小、数量；模型必须先请求固定读取工具，删除单独确认；不申请广泛存储权限 | 已实现单 URI 读取/删除；不会自动把选择内容作为初始附件 |
| 媒体/系统设置 | 仅允许清单中的公开非安全设置键；不允许安全设置、权限、默认输入法、网络安全或开发者选项 | 已实现四个 `Settings.System` 键的读写，写入需要 `WRITE_SETTINGS` 和一次确认 |
| 相机/麦克风 | 仅一次任务内单帧/短音频采集；采集前再次确认；无后台/持续采集或落盘 | 已实现有界内存 JPEG/WAV；硬件权限与真机行为仍待验收 |
| 通知 | 只读当前活动通知，且指定一个目标包；限制字段和数量；通知相关后续行为仍逐次确认；不自动回复、历史扫描或后台规则 | 已实现最多 5 条活动通知；通知访问需系统设置页单独开启 |
| 短信/电话 | 一个明确号码的一条读取或一次发送/拨号；所有操作逐次确认；不批量扫描、删除或录音 | 已实现 READ/SEND SMS、通话记录读取和一次拨号；运行时权限与运营商行为仍待验收 |
| 联系人/日历 | 读取单个明确记录；创建/修改/删除使用固定字段和逐次确认；不扫描 provider、不保存历史 | 已实现静态工具和平台适配；联系人/日历权限与厂商 provider 仍待验收 |
| 屏幕录制 | 若评估，单独使用 Android MediaProjection 每次系统同意、限当前任务并及时停止；和单帧截图分开控制 | 持续录屏比现有单帧截图风险更高；当前未实现 |

ADR 0026 记录上述最小权限实现和剩余人工验收边界。所有阶段 6 能力仍默认关闭，不能通过设备代理总开关一次性打开；屏幕录制、后台规则、批量扫描和任意文件管理不在本阶段。

## 10. 建议文件布局

```text
ai-api/src/main/kotlin/dev/zeroinput/ai/api/
  DeviceAgentModels.kt
  DeviceAgentPolicy.kt
  DeviceActionCatalog.kt

app/src/main/kotlin/dev/zeroinput/ime/ai/device/
  DeviceAgentAccessibilityService.kt
  DeviceAgentAccessibilityBroker.kt
  DeviceAgentCoordinator.kt
  DeviceAgentPolicyEvaluator.kt
  DeviceAgentActionDispatcher.kt
  DeviceAgentApprovalController.kt
  DeviceAgentRedactor.kt
  DeviceAgentLimits.kt
  DeviceAgentActivity.kt
  DeviceAgentSettingsActivity.kt

app/src/test/kotlin/dev/zeroinput/ime/ai/device/
  DeviceAgentPolicyTest.kt
  DeviceActionCatalogTest.kt
  DeviceAgentCoordinatorTest.kt
  DeviceAgentToolProtocolTest.kt
  DeviceAgentGenerationTest.kt

app/src/androidTest/kotlin/dev/zeroinput/ime/ai/device/
  DeviceAgentAccessibilityFixtureTest.kt
  DeviceAgentRevocationDeviceTest.kt

docs/adr/0025-ai-device-agent-boundary.md
```

实际命名以现有包结构为准；单个类只承担一个生命周期或策略职责，避免把 Accessibility、网络、审批和 UI 堆进一个代理类。

## 11. 测试与验收矩阵

### 11.1 策略和契约

- 默认关闭、网络关闭、AI 开关关闭和未同意声明时不能创建任务。
- 未授权、服务断开、锁屏、前台包不匹配、窗口不唯一和目标不在白名单时全部拒绝。
- 完整范围不能绕过硬阻断；白名单不因应用显示名变化而扩大。
- 每步审批、任务审批和安全动作自动执行的边界精确可观测。
- 未知工具、未知参数、缺失参数、重复调用 ID、重放旧快照和跨任务结果均拒绝。

### 11.2 Accessibility 和生命周期

- 事件回调不读取正文；显式观察才读取节点。
- 节点深度、数量、文本、窗口数、坐标和截图预算超限时停止并报告不完整。
- 节点回收、缓存禁用、服务销毁、Activity 重建、旋转、锁屏和后台都不留下旧对象或任务。
- 应用切换、权限撤销、设置变化、任务取消、网络取消和 provider 超时使旧动作失效。
- 坐标点击只能使用当前快照和当前屏幕尺寸；过期 ref 不得点击新页面。

### 11.3 隐私与安全

- 密码、PIN、邮箱、URI、`NO_PERSONALIZED_LEARNING` 和敏感节点无法观察或输入。
- 阶段 0–5 的无障碍动作不读取系统/私有剪贴板、通知、短信、联系人、日历、文件和外部编辑器；阶段 6 仅通过独立工具读取用户明确指定的单个目标。
- 工具结果、截图、输入参数和页面文本不进入日志、异常、崩溃快照或默认历史。
- 用户取消、清除数据和删除任务后，排队的网络、动作和持久化结果不能复活。
- 页面注入内容不能提升权限、改变审批策略、创建新工具或请求未授权数据。

### 11.4 构建和设备验证

至少运行：

```powershell
./gradlew.bat :ai-api:test :app:testDebugUnitTest privacyCheck :app:lintDebug `
  :app:assembleDebug "-Pandroid.injected.build.abi=x86_64"
```

2026-10-21 本机验证记录：

- 使用 JDK 21 运行 Gradle（JDK 17 供 Java 编译工具链使用）执行 `:ai-api:test :app:testDebugUnitTest privacyCheck :app:lintDebug :app:assembleDebug -Pandroid.injected.build.abi=x86_64 --no-parallel`，全部通过；`app:lintDebug` 覆盖了页面引用 API 28 常量在 minSdk 26 下的兼容路径。
- 使用 `:app:assembleRelease -PrequireRime=true --no-parallel`，三 ABI 的真实 librime Release 构建、R8 和 Release Lint 全部通过。Release APK 未配置正式签名。
- Android 16 / API 36 x86_64 模拟器上，页面引用与设备代理专项 instrumentation 共 7/7 通过，覆盖页面引用来源切换/锁屏/服务撤权、设备代理敏感字段脱敏、单次审批点击、前台切换、锁屏、服务撤权和 Activity 实例重建。
- 全量 `:app:connectedDebugAndroidTest --no-parallel` 发现 392 项，8 项失败、14 项跳过。`DictionaryManagementUiTest.publicFixtureSearchFitsSmallPortraitAndLandscapeInBothThemes`、`FullscreenInputTest.sameFullscreenEditorAcceptsInputAfterRepeatedLiveRotation` 和 `ClipboardGuardOverlayTest.overlaysReplaceOneWindowDismissWithoutResurfacingAndExpire` 的失败单独重跑通过，属于整套运行时的 fixture/启动时序失败。以下 5 项单独重跑仍失败，均为当前工作区已有键盘尺寸/布局断言，与设备代理和页面引用改动无调用路径关联：`InputPanelTest.associationsKeepKeyboardSizeAndCandidateIdentityAcrossThemesAndOrientations`、`KeyboardAppearanceTest.attachedPublicAssociationsRenderInBothModes`、`KeyboardExperienceTest.idleComposingAndToolsShareTheSameCompactHeaderWithoutMovingKeys`、`KeyboardWindowIntegrationTest.floatingWindowPassesOutsideTouchesAndAllModesRemainUsable`、`NineKeyPanelTest.nineKeyReadingsAndToolbarFitSmallLargeAndLandscapeLayouts`。这些布局失败仍需后续单独修复后重跑全量套件。
- 生命周期：`DeviceAgentCoordinator` 由进程级 `AppGraph` 持有；任务、模型历史、审批回调和选中文件 URI 没有持久化存储；任务与目标包输入禁用 View 状态恢复。使用合成草稿从正常 Activity 栈进入任务页，按 Home 后执行 `am kill --user 0 dev.zeroinput.ime.debug`，确认旧 PID 消失且最近任务记录保留；点击最近任务卡片恢复同一 task record。新进程中的任务页显示草稿、目标包、附件状态和时间线均为空，停止按钮禁用。Android 16 x86_64 模拟器上的最近任务恢复通过；操作系统低内存回收与物理设备行为仍待验收。
- 本机仅连接 Android 16 x86_64 模拟器，没有物理设备。AI 设置页显示总开关和联网开关关闭，未读取或使用任何凭据；因此未发起真实 provider 请求。阶段 6 的通知访问、运行时权限、WRITE_SETTINGS、相机/麦克风硬件、短信/电话和厂商联系人/日历 provider 也未在真实设备执行。
- 本轮安全修复完成后的设备复测未能启动：ADB 中 `emulator-5554` 显示 `offline`，因此未重新运行专项 instrumentation，也未验证新增的阶段 6 确认界面行为；此前模拟器专项通过记录不覆盖本轮新增改动。

Android 16 x86_64 模拟器上的进程终止与最近任务卡片恢复已完成；复测使用合成文本，任务未启动，旧 PID 消失，点击原任务卡片后草稿、附件状态和时间线为空且没有活动任务。系统低内存回收仍需单独验收。不要使用真实通知、联系人、消息、附件或 provider 凭据测试。物理设备和真实 provider 仍需按相应权限披露逐项验收，不能以模拟器或伪传输替代。

## 12. 发布与产品风险

- Google Play 对非残障用途的 Accessibility API 有显著披露、用户同意和自动化限制。完整自主代理可能无法按 Play 政策发布；发布渠道必须在阶段 0 明确。
- 辅助功能授权的系统能力很宽，应用内白名单和审批只能减少误用，不能把平台授权变成细粒度系统权限。产品文案必须明确这一事实。
- 不同厂商对节点树、手势、敏感标记和窗口焦点的实现不同。无法稳定识别的内容和动作必须失败关闭，而不是退化到盲点坐标点击。
- 第三方 AI 服务可能保存或训练请求内容。ZeroInput 可以限制发送和关闭协议级存储，但不能替用户控制供应商留存；配置页和任务开始前必须重复提示。
- 现有工作区有未提交的 AI 流处理改动；实现阶段必须先确认这些改动的归属，不能覆盖或回退。

## 13. 已确认决策

用户已确认采用侧载/自有分发，不将 Google Play 作为首发渠道，并允许阶段 0–5 当前动作目录及阶段 6 的直接读取/操作范围。通知相关行为保持逐次确认，不提供后台规则或自动回复。阶段 6 默认关闭，按类别披露、授权和逐次审批；代理历史默认不保存。Activity 实例重建、Android 16 模拟器进程终止后的最近任务卡片恢复与 provider 伪传输已验证；物理设备兼容性、系统低内存回收、阶段 6 真机权限/硬件行为和真实模型服务请求仍需在发布前单独验收。
