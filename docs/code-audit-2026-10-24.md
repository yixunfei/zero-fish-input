# ZeroInput 当前工作树审计简报

日期：2026-10-24  
基线：`main` @ `367c58c018ec4667fea9e9245d0a6be6a9895d0a`，包含工作树中未提交的个性化排序与设置改动。  
范围：隐私与数据边界、输入与异步生命周期、AI／阶段 6 设备能力、设置和键盘 UI、构建与设备测试。历史报告和 `.tmp-evidence` 只作线索，结论依据当前源码、调用链及本次运行结果。未修改业务源码、测试或 Git 历史；本报告不代表已穷尽所有缺陷。

## 摘要

- 确认 **1 项高优先级、3 项中优先级产品问题**：阶段 6 的取消不能中断部分 provider 操作；联系人重命名字段与契约不符；候选出现／消失会使键盘头部改变 48dp；英文设置在 320dp 窄屏溢出。
- 另有 **联系人聚合账户覆盖缺口、阈值弹窗生命周期风险、AI 测试契约漂移和渲染测试不稳定**。阶段 6 的联系人问题尚未在真实 provider 上验收。
- 本次重点审阅路径未发现新的未授权联网、个人数据枚举、系统剪贴板正文读取、备份开启或 Release 可调试配置。这是限定范围的检查结果，不等于完整安全证明。
- 当前全量设备测试为红，Release 未配置正式签名，**不能据此称项目整体通过或可发布**。

## 发现（按优先级）

### 高：阶段 6 的取消没有传递到多类阻塞 provider 调用

**证据** [`AndroidDeviceStage6Platform.kt`](../app/src/main/kotlin/dev/zeroinput/ime/ai/device/AndroidDeviceStage6Platform.kt#L34-L60) 将 `DeviceStage6Cancellation` 传给文件／媒体读取，却在分发 SMS、电话、联系人和日历时丢弃。短信和通话记录查询使用无取消信号的四参数 `query`（同文件 [L154-L165](../app/src/main/kotlin/dev/zeroinput/ime/ai/device/AndroidDeviceStage6Platform.kt#L154-L165)、[L186-L197](../app/src/main/kotlin/dev/zeroinput/ime/ai/device/AndroidDeviceStage6Platform.kt#L186-L197)）；联系人和日历亦有同类查询、写入和批处理。控制器只能在线程进入或退出这些调用前后检查 `stillValid`（[`DeviceStage6Controller.kt:188-216`](../app/src/main/kotlin/dev/zeroinput/ime/ai/device/DeviceStage6Controller.kt#L188-L216)）。这违反了取消接口注释所述的 provider 中断设计（[`DeviceStage6Cancellation.kt:3-9`](../app/src/main/kotlin/dev/zeroinput/ime/ai/device/DeviceStage6Cancellation.kt#L3-L9)）。单线程 Stage 6 执行器的注释也明确承认 Binder 可能阻塞（[`AppGraph.kt:84-90`](../app/src/main/kotlin/dev/zeroinput/ime/AppGraph.kt#L84-L90)）。

**影响** 慢或不响应的系统 provider 可使取消、撤权或会话失效不能及时结束正在执行的操作，并长期占用唯一 Stage 6 worker，拖延后续操作。旧结果在返回后仍有代次过滤，但“结果不交付”不等于“操作已取消”。Android 原生写入和相机／麦克风也未使用该取消对象；传感器已有轮询与资源释放，实际中断延迟仍需真机测量。

**建议** 读取采用支持 `CancellationSignal` 的 provider 重载，并注册／注销同一信号；对不支持取消的原生写入和传感器操作定义可验证的完成／超时语义与资源所有权。使用可控阻塞 provider 做“运行中取消→解除阻塞→后续任务可执行”的设备测试；仅增加线程数不会使取消生效。

### 中：`update_contact` 改写结构化名而非约定的显示名

**证据** 工具契约和审批文案将参数 `new_display_name` 定义为重命名联系人（[`DeviceStage6ToolCatalog.kt:90-92`](../ai-api/src/main/kotlin/dev/zeroinput/ai/api/DeviceStage6ToolCatalog.kt#L90-L92)、[`DeviceAgentDescriptions.kt:119-142`](../app/src/main/kotlin/dev/zeroinput/ime/ai/device/DeviceAgentDescriptions.kt#L119-L142)）。实现更新／插入的却只有 `StructuredName.GIVEN_NAME`（[`AndroidDeviceStage6Platform.kt:280-301`](../app/src/main/kotlin/dev/zeroinput/ime/ai/device/AndroidDeviceStage6Platform.kt#L280-L301)），后续又按聚合 `DISPLAY_NAME` 查找（同文件 [L317-L323](../app/src/main/kotlin/dev/zeroinput/ime/ai/device/AndroidDeviceStage6Platform.kt#L317-L323)）。项目测试没有该 Android adapter 的多账户／显示名覆盖。

**影响** `new_display_name` 被当作名（given name）而非完整显示名处理；在保留姓、昵称或其他结构化字段的联系人上，生成名称可能与用户批准的新名不一致，之后按该新名查询失败。不同 provider 的显示名生成规则需在真机验收，但参数与写入字段的语义不一致已由当前代码确定。

**建议** 先明确产品是改聚合显示名还是结构化姓／名，再统一工具描述、审批文案、创建和更新实现。不要同时把两种语义写入同一字段；补充重命名后可按新名精确查询的端到端测试。

### 中：候选出现／清空使键盘头部改变 48dp

**证据** `applyHeaderHeight()` 仅在候选可见时使用视口规划高度，否则强制 48dp（[`ZeroInputView.kt:1152-1164`](../ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/ZeroInputView.kt#L1152-L1164)），候选与工具又互斥显示（同文件 [L1057-L1068](../ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/ZeroInputView.kt#L1057-L1068)）。正常竖屏的堆叠／单行规划为 96dp／48dp（[`KeyboardViewportPolicy.kt:37,55-56`](../ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/KeyboardViewportPolicy.kt#L37-L56)）。该行为由当前 HEAD `367c58c` 引入，与 `AGENTS.md:160` 的“优先稳定尺寸、动态内容不得无意义跳动”要求冲突。

**复现** 九键清空读音在直接 View 测试中稳定复现高度 `796→670px`，恰为密度 2.625 下的 48dp（[`NineKeyPanelTest.kt:29-60`](../app/src/androidTest/kotlin/dev/zeroinput/ime/NineKeyPanelTest.kt#L29-L60)）。`InputPanelTest` 的联想前后、`KeyboardExperienceTest` 的空态／组合／工具、`KeyboardAppearanceTest` 的附着 View 均出现同类高度差异。真实 IME 浮动窗口切换场景也失败，但其断言包含窗口 inset／布局因素，未单独归因于头部。

**建议** 明确“空态紧凑”与“组合时按键位置稳定”的产品优先级，在 `ZeroInputView` 统一头部预算；保留紧凑横屏规则，同时让空态、候选、联想和工具切换不改变按键基准位置。修复产品或测试前先确认契约，勿仅为绿灯改写高度期望。

### 中：英文设置标签在 320dp 下触发布局约束失败

**证据与复现** `settingSwitch` 使用固定 16sp 文本，父宽 `MATCH_PARENT`，无明确的文本／开关分行或缩写策略（[`SettingsScreenView.kt:440-449`](../app/src/main/kotlin/dev/zeroinput/ime/settings/SettingsScreenView.kt#L440-L449)）。隔离运行 [`SettingsPanelTest.chineseControlsFitSmallScreensAndExposeTheSelectedModes`](../app/src/androidTest/kotlin/dev/zeroinput/ime/SettingsPanelTest.kt#L90-L102) 仍失败：`Learn from commits (space, enter, etc.)` 可用宽度 `557px`，需要 `759.0px`，视图宽 `736px`。

**影响** 当前可点击标签未通过项目定义的窄屏适配契约。实际换行、截断和控件触控表现仍需渲染验收；其他长标签和大字体需要同类检查。

**建议** 让文本在保留可读性的前提下换行或采用文本／开关两行布局，避免以缩小字号或删去关键说明规避问题；补英文、320dp、大字体、深浅主题的渲染检查。

## 设计缺口与风险

### 中：聚合联系人含多个 raw account 时，更新／删除直接失败

[`findRawContact()`](../app/src/main/kotlin/dev/zeroinput/ime/ai/device/AndroidDeviceStage6Platform.kt#L325-L333) 最多读取两行，却用 `singleOrNull()` 接受结果；同名的聚合联系人可以唯一，但其本地／SIM／同步账户原始记录不唯一，因此 [`updateContact()` 和 `deleteContact()`（L280-L315）](../app/src/main/kotlin/dev/zeroinput/ime/ai/device/AndroidDeviceStage6Platform.kt#L280-L315) 会拒绝操作。当前属于**源码已确定的多账户能力缺口**，不是已证实的越权或部分写入；失败关闭本身应保留。需明确是否支持所有原始行的原子变更，或拒绝并返回精确的多账户结果，绝不可默认只改第一行。

### 低：主设置页的阈值弹窗未纳入宿主销毁管理，截图保护未验收

主设置页直接调用 [`PersonalRankingSettings.show()`](../app/src/main/kotlin/dev/zeroinput/ime/settings/MainActivity.kt#L159-L166)，未持有返回的 `AlertDialog`，`onDestroy()` 仅关闭 AI 配置弹窗（同文件 [L134-L150](../app/src/main/kotlin/dev/zeroinput/ime/settings/MainActivity.kt#L134-L150)）；词库页则持有并销毁 `phraseDialog`（[`UserDictionaryActivity.kt:81-88,211-218`](../app/src/main/kotlin/dev/zeroinput/ime/settings/UserDictionaryActivity.kt#L81-L218)）。阈值编辑器自身有 `FLAG_SECURE`（[`PersonalRankingSettings.kt:18-37`](../app/src/main/kotlin/dev/zeroinput/ime/settings/PersonalRankingSettings.kt#L18-L37)），但主设置页在 `onCreate` 未设置该窗口标记。建议统一宿主与弹窗所有权、旋转／退出清理，并断言独立窗口的实际 `FLAG_SECURE`；目前未把它认定为已发生泄漏。

### 低：`ZeroInputService` 已超一般文件规模建议

[`ZeroInputService.kt`](../app/src/main/kotlin/dev/zeroinput/ime/ZeroInputService.kt) 为 1,988 行，超过 `AGENTS.md:192` 的一般 1,500 行建议，尚在 2,500 行例外上限内。它同时编排 IME 会话、AI 草稿与请求、隐私失效、手写／滑行、剪贴板及设备代理。行数本身不是功能 bug，但使跨域状态失效审查与测试成本偏高；后续按明确状态所有权拆分，而非机械分段。

## 测试契约与稳定性

1. **AI 控件契约漂移（中，测试债务）**：当前 HEAD 已将普通插入／全选改为“插入全部回答／插入所选段落／按段选择”（[`AiWorkbenchPanelView.kt:591-616`](../ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/AiWorkbenchPanelView.kt#L591-L616)）。`AiStorageFailurePanelTest`、`AiWorkbenchPanelTest`、`AiWorkbenchFlowTest`、`PageReferencePlatformTest`、`AiImeInteractionTest` 仍查找旧 `ai_insert`／`ai_select_all`，例如 [`AiStorageFailurePanelTest.kt:33-35`](../app/src/androidTest/kotlin/dev/zeroinput/ime/ai/AiStorageFailurePanelTest.kt#L33-L35) 与 [`PageReferencePlatformTest.kt:65`](../app/src/androidTest/kotlin/dev/zeroinput/ime/ai/page/PageReferencePlatformTest.kt#L65)。这些失败不能证明 AI 插入功能消失；此前 `AiImeInteractionTest` 的 `editing=false` 是其取反等待条件的正常结果，不据此判为 AI 会话故障。改为稳定 ID／新语义断言，同时保留显式插入、撤权、隐私与存储失败后的行为验证。
2. **词典管理页渲染失败未复现／归因（低）**：整套运行中 `DictionaryManagementUiTest.publicFixtureSearchFitsSmallPortraitAndLandscapeInBothThemes` 曾因位图颜色数不大于 8 失败，隔离复跑通过。该测试对已附着 Activity 手动改尺寸并数颜色（[`DictionaryManagementUiTest.kt:58-75`](../app/src/androidTest/kotlin/dev/zeroinput/ime/DictionaryManagementUiTest.kt#L58-L75)），尚不足以区分产品渲染、测试夹具和模拟器条件。保存失败变体与控件级可观测断言，避免仅以颜色数量判断可用性。
3. **缺少真实 Stage 6 平台覆盖（中）**：现有自动化覆盖策略、授权、代次及资源边界，但未覆盖 Android 联系人／日历／短信 provider 的取消、显示名和聚合账户行为；不能把纯 JVM 通过当作这些路径的验收。
4. **测试工具与结果归档**：整套运行时出现过 shell `uiautomator dump` 的 `UiAutomationService already registered`，不是 ZeroInput 崩溃。后续串行管理 UIAutomation 并保存每次独立运行日志；聚焦运行会覆盖 Gradle 当前 XML，不能直接替代全量报告。

## 本次验证

| 检查 | 结果与边界 |
| --- | --- |
| `testDebugUnitTest privacyCheck :app:lintDebug :app:assembleDebug --rerun-tasks`（x86_64） | 通过；427 个任务实际执行。142 个模块结果 XML 合计 803 项，0 failure/error，2 skipped；Lint 报告为 `No issues found`。跳过项是 2 个依赖外部 live host 的 AI 测试。历史证据副本未计入。 |
| `:app:assembleRelease -PrequireRime=true --no-parallel` | 通过；3 个 ABI 的 APK 均生成。R8／资源收缩和 librime 要求已启用。 |
| APK 签名与 Release manifest | `apksigner verify --print-certs` 对 Release 报 `DOES NOT VERIFY`、`Missing META-INF/MANIFEST.MF`；Debug APK 验证为 Android Debug 证书。Release manifest 保留 `allowBackup=false`、`usesCleartextTraffic=false`，未出现 `debuggable=true`。正式签名、完整发布验收及三 ABI 运行验证未完成。 |
| API 36 模拟器全量 `connectedDebugAndroidTest` | 该次全量运行的 XML 为 **397 项、17 failure、0 error、12 skipped**。失败包含上述键盘、窄屏、旧 AI 标签、词典渲染问题；整体验证不通过。 |
| 失败分组聚焦复跑 | 七类共 46 项，10 failure；词典渲染隔离通过。另一组 12 项，6 failure（4 个旧 AI 控件契约、九键高度、浮动窗口高度）。聚焦结果只用于归因，不与全量相加或替代全量。 |
| 新增个性化设备覆盖 | `PersonalRankingSettingsTest` + `PersonalLearningRimeTest` **2/2 通过**，覆盖真实 Rime 全拼／简拼词频提升、阈值回退及两个设置页共享阈值。XML 时间戳 `2026-10-24T10:08:55`；不消除其他设备失败。 |

最新聚焦结果位于 `app/build/outputs/androidTest-results/connected/debug/TEST-ZeroInputModelApi36(AVD) - 16-_app-.xml`（最后一轮为个性化测试）；其内容是 Gradle 生成文件，后续设备运行会覆盖。本文保留全量与聚焦统计，未把聚焦结果当成全量通过证据。

## 未验证范围与优先顺序

- 尚需真机／真实系统 provider 验收：运行时权限、联系人多账户及显示名、日历／短信、阻塞 provider 取消、相机／麦克风实际资源释放。
- 尚需物理设备的旋转、分屏／浮动窗口、系统 inset、大字体及屏幕阅读器检查。截图已生成但未进行视觉审阅；自动尺寸／颜色检查不覆盖真实视觉与交互体验。
- 词典整套失败需用固定夹具重复运行；AI 控件测试更新后需重新全量跑，不能根据旧标签失败推断隐私链路通过或失败。
- 本次是审计而非修复交付；未变更业务代码，未创建提交。建议先修复阶段 6 取消与联系人契约并补平台测试，再统一键盘尺寸／窄屏布局，最后更新测试选择器、清理脆弱断言并重跑全量设备验证。

**证据置信度：B。** 源码契约、确定性 UI 失败和本次构建／测试均有直接证据；真实 provider、硬件和完整视觉／物理设备范围仍是明确缺口。
