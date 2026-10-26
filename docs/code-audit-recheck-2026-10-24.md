# ZeroInput 修复后复查简报

日期：2026-10-24
基线：`main` @ `e0a9cb81f06291a004be9de7845e7b06cdd84201`。本轮开始时基线为 `367c58c018ec4667fea9e9245d0a6be6a9895d0a`；复查期间用户提交 `a8e66468fe59650a6c0d2a5df61e78b4e24b3285` 并完成 `ours` 历史合并。已核对合并前后树相同、源码文件时间早于测试、当前工作树无未提交差异；聚焦测试启动于该合并之后。
范围：上一轮审计项、相关改动及回归风险。只读审查源码、历史差异和测试结果；未修改业务源码、测试、提交或回滚用户改动。本文新增为复查记录，历史报告和归档证据仅作线索。

## 结论

- **未发现可确认的新产品回归。** 上一轮产品问题在当前源码中已有对应修复；本轮相关模拟器测试通过。
- **设备全量套件仍有一次失败，不能记为全绿。** 404 个 XML 用例中 391 通过、1 失败、12 跳过；失败是 Rime 就绪等待超时，不是本轮已修复路径的新失败。
- **未闭合的是测试可靠性与硬件验收，而非新增已证实缺陷。** 失败用例所属测试类随后 5/5 通过；该通过不消除全量失败，也不证明根因已经消失。
- 证据置信度 **B**：源码和模拟器证据较充分；真机、厂商 provider、真实传感器与部分可访问性仍未验证。

## 仍存问题与风险

### 中：Rime 就绪等待在整套运行中偶发超时，根因未确定

全量运行中 `AiImeInteractionTest.typingBeforeDraftWarmupStillConvertsChineseAndLeavesTheHostUntouched` 在 [`AiImeInteractionTest.kt:31-36`](../app/src/androidTest/kotlin/dev/zeroinput/ime/AiImeInteractionTest.kt#L31-L36) 的 60 秒等待后断言 `Shared Rime runtime must finish startup before the draft worker is blocked` 失败。失败发生在草稿 worker 被阻塞和 AI 输入断言之前，不能据此认定中文转换或 AI 隔离逻辑错误。

随后不修改代码、不延长等待，聚焦运行整个 `AiImeInteractionTest`，5/5 通过；原失败用例耗时 63.390 秒，其中包含一次约 60 秒的就绪等待。它表现为接近时限的启动时序问题，但当前日志没有区分 `INITIALIZING`、`FAILED` 或 native 工作占用，**不足以建立产品、测试夹具或模拟器负载之间的确定因果关系**。`RimeRuntime.initialize()` 在 native 初始化完成前保留进行中所有权（[`RimeRuntime.kt:110-180`](../engine-rime/src/main/kotlin/dev/zeroinput/engine/rime/RimeRuntime.kt#L110-L180)），可作为后续观测点，但本次未把它认定为根因。

建议后续在测试失败时记录运行态、公开错误类别与初始化耗时，并以固定 APK 做有界冷启动重复运行。当前不通过扩大超时掩盖问题，也不根据一次聚焦通过声称稳定性已解决。

### 低：`ZeroInputService` 的跨域生命周期复杂度仍然保留

[`ZeroInputService.kt`](../app/src/main/kotlin/dev/zeroinput/ime/ZeroInputService.kt) 仍约 1,988 行，超过项目一般 1,500 行建议、低于 2,500 行例外上限。该问题不是本轮引入，也没有证据证明它造成功能回归；它继续增加输入会话、AI、传感器、剪贴板和隐私失效共同演进时的审查成本。保留现状是本轮控制修复范围的选择，不代表复杂度已治理。

### 中：硬件和真实平台验收仍未闭合

- 传感器修复的静态所有权合理：麦克风使用非阻塞读取和可取消等待，相机有晚到回调和缓冲区清理；但 [`DeviceStage6SensorCaptureTest.kt`](../app/src/test/kotlin/dev/zeroinput/ime/ai/device/DeviceStage6SensorCaptureTest.kt) 仅直接覆盖 `TransientCaptureBuffer`。真实启动—取消—释放时延、权限撤销、相机会话回调及厂商硬件行为没有实测。
- provider 读取已有取消协议适配；不可取消的原生写入、短信／电话请求仍只能失效旧结果，不能回滚已派发操作。若 provider 忽略取消，唯一 worker 仍可能占用到返回。这是已记录的平台边界，不是“所有操作都能立即撤销”。
- 模拟器联系人测试使用合成记录，覆盖全名及多 raw 拒绝；它不等于各厂商同步账户、真实权限撤销和数据库异常行为验收。
- 旋转、分屏／浮动窗口、物理屏幕尺寸、系统 inset、屏幕阅读器及全部手动视觉体验仍需真机检查。自动布局断言不能替代这些验收。

## 上一轮问题复核

| 项目 | 当前判断与证据 |
| --- | --- |
| 阶段 6 provider 读取忽略取消 | **已修复。** [`DeviceStage6Queries.kt`](../app/src/main/kotlin/dev/zeroinput/ime/ai/device/DeviceStage6Queries.kt) 统一持有并注销 `CancellationSignal`，短信、通话记录、联系人和日历均走该路径；控制器交付前仍校验代次及取消状态。阻塞 provider、信号注销及不可取消写入后的回退新增均有设备测试。 |
| `new_display_name` 写入 given name | **已修复。** [`AndroidDeviceStage6Platform.kt:322-333`](../app/src/main/kotlin/dev/zeroinput/ime/ai/device/AndroidDeviceStage6Platform.kt#L322-L333) 写入完整显示名并清除旧结构化／读音字段；真实 Android ContactsProvider 合成记录验证按新名精确读取、旧名不再命中。 |
| 聚合联系人多个 raw account | **按已确认的失败关闭策略解决。** 更新和删除在发现多条 raw contact 时明确拒绝，未采取只改第一条的隐性行为；测试验证两条记录均不变。它仍是功能限制，而不是已修复的跨账户能力。 |
| 候选／工具切换改变键盘头部 | **已修复。** [`ZeroInputView.kt:1152-1161`](../ime-ui/src/main/kotlin/dev/zeroinput/ime/ui/ZeroInputView.kt#L1152-L1161) 始终使用视口规划的 header 高度，保留紧凑横屏策略；直接 View 和真实 IME 工具栏测试覆盖高度与按键坐标。 |
| 英文设置在 320 dp 下溢出 | **已通过当前自动化验收。** 长开关允许换行，导航／命令／模式控件按内容增长；中英、双主题和字体缩放的窄屏测试通过。真实视觉和辅助技术仍按上述边界保留。 |
| 阈值弹窗生命周期与截图保护 | **已修复。** 两个宿主均持有并在销毁时关闭弹窗，字段在 dismiss 时清空；[`PersonalRankingSettingsTest.kt:22-49`](../app/src/androidTest/kotlin/dev/zeroinput/ime/PersonalRankingSettingsTest.kt#L22-L49) 覆盖 recreate／close、脱离窗口及独立窗口 `FLAG_SECURE`。 |
| AI 测试使用旧插入控件选择器 | **已修复。** 当前测试改用 `ai_insert_result` 及现行分段选择语义；未删除显式插入、隐私撤权、宿主编辑器不变和存储失败断言。未发现旧 `ai_insert`／`ai_select_all` 选择器残留。 |
| 词典管理页渲染测试不稳定 | **本轮通过，未复现旧失败。** 测试改用脱离窗口的合成视口，先保存截图再断言边界、触控高度与完整文本。尚无新证据将该旧失败认定为产品回归。 |
| 组合构建的测试 fixture 依赖 | **已修复。** 新鲜 `--rerun-tasks` 构建同时完成 Debug APK、测试 APK、JVM、隐私及 Lint 检查。 |

## 本轮验证

| 检查 | 结果与边界 |
| --- | --- |
| `testDebugUnitTest privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks`，x86_64、串行 | **通过**，457 个任务实际执行。当前构建目录内 142 个 JVM XML：803 项，801 通过、2 跳过、0 failure/error；Lint 为 `No issues found`。递归统计若包含 `.tmp-evidence` 会混入历史副本，已排除。该命令未追加 `-PrequireRime=true`；当前生成的 Debug `HAS_NATIVE_RIME=true`，且设备公开词典／语法转换用例覆盖真实 Rime，不将此结果表述为 `requireRime` 构建验收。 |
| API 36 x86_64 模拟器全量 `:app:connectedDebugAndroidTest` | **1 项失败。** XML 404 项：391 通过、1 失败、12 跳过、0 error；运行约 27 分 20 秒。12 个跳过来自实时 AI／可选公开资源或明确设备与剪贴板前置条件，不计作通过。 |
| 随后聚焦 `AiImeInteractionTest` | **5/5 通过**，构建成功；仅用于判断可重复性，不替代全量结果。当前聚焦 XML 位于 [`TEST-ZeroInputModelApi36(AVD) - 16-_app-.xml`](../app/build/outputs/androidTest-results/connected/debug/TEST-ZeroInputModelApi36%28AVD%29%20-%2016-_app-.xml)。 |
| 额外 ADB 单方法循环 | **未执行测试。** Gradle 聚焦运行后设备包列表已不含目标 instrumentation，调用返回 `INSTRUMENTATION_FAILED`；属于测试工具调用问题，不计入产品失败或通过数。 |
| 源码差异与工作树 | `git diff --check` 未报空白错误；审查时所有用户修改均保留。本文件是唯一由本轮新增的源目录文件，未修改业务源码／测试，未创建提交。 |

## Aegis 验证边界

- 关键判断：上一轮修复在限定模拟器范围通过；设备全量测试仍需处理一项间歇性 Rime 启动等待异常。
- 未以局部绿灯替代全量、未将测试工具错误归类为产品 bug，也未将静态传感器检查当成硬件验收。
- 复杂度控制：本轮没有新增生产分支或兼容层；保留 `ZeroInputService` 既有复杂度和传感器真实覆盖缺口，未借机扩大修复。
- 最高价值的下一项验证：记录失败时 Rime 状态及初始化耗时，在固定 APK、固定模拟器上重复冷启动，并用真机完成 provider／传感器验收。
- 本次为代码复查，不是 Release、三 ABI 运行或正式签名验收；历史 Release 结果不作为本轮新证据。
