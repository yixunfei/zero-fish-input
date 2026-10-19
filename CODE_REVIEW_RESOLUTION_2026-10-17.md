# 2026-10-17 代码审查反馈处置记录

处置日期：2026-10-18。原始清单：[CODE_REVIEW_FEEDBACK_2026-10-17.md](CODE_REVIEW_FEEDBACK_2026-10-17.md)。
保留原报告；本文件记录当前工作区实现与实际验证，不修改原审查结论的历史证据。

34 项均已核对：31 项完成代码修复或加固，M7 部分缓解，P2 保留为有界性能观察，P5 待设备确认。
未创建 Git 提交，未发布资源或 APK。未增加旧数据格式兼容层，也未放宽词典过滤、认证、网络或资源完整性边界。

## 中优先级

| 编号 | 处置与当前行为 | 实现 / 回归证据 |
| --- | --- | --- |
| M1 | 已修复。只有存在 native Rime 时才验证 OpenCC 资产，与准备任务守卫一致；正式构建仍要求真实 Rime。 | `engine-rime/build.gradle.kts` 的 `verifyOpenCcAssets`。已通过真实源码构建；未另建缺少 third_party 的干净检出。 |
| M2 | 已修复。过滤尚未准备好时，Space 按 Enter 语义提交原始组合；继续隐藏未经过滤的候选，避免复活已删除词项。 | `InputSessionController.kt`；`DictionaryCommitTest` 覆盖未就绪 Space 与过滤恢复。 |
| M3 | 已修复。Anthropic thinking 预算严格小于输出上限；不足的显式上限在传输前拒绝。 | `AiModels.kt`、`AiRequestEncoder.kt`；`AiModelsTest`、`AiProtocolCodecTest`、`AiTransportProtocolTest`。真实厂商端点未联调。 |
| M4 | 已修复。运行期引擎缺失显示失败状态；重试不再被旧的已安装预热上下文短路，可在当前会话准备替代引擎。 | `ZeroInputService.kt`；`PreparedEngineHandoffTest` 验证失败、保留字面输入与替代引擎转换。真实 JNI 异常注入尚未做。 |
| M5 | 已修复操作结果与提交点。先暂存无正文索引，再原子提交正文；提交后不再以取消报告失败；提交前失败回滚，正文回滚失败则拒绝后续访问。 | `SecureClipboardVault.kt`；`SecureClipboardTransactionTest` 覆盖索引失败、写后抛错、提交前后取消、清除竞态与回滚失败。两文件不具备进程崩溃原子性，见下方边界。 |
| M6 | 已修复重复渲染路径。删除服务提前刷新选项；UI 对 header、选项与图标保留末值，主题变化使缓存失效。 | `ZeroInputService.kt`、`ZeroInputView.kt`；UI 单元测试、Lint 与构建通过。未测 Perfetto 耗时收益。 |
| M7 | **部分缓解**。完整哈希后建立最多 64 条进程内缓存；固定摘要与 dev/ino/size/mtime/ctime 均相同时复用。元数据不受支持时始终完整哈希。未采用持久化 size/mtime 票据。 | `ResourceFileVerifier.kt`、`PublicResourceStore.kt`；`ResourceFileVerifierTest` 覆盖同尺寸修改、恢复 mtime、身份变化及不支持身份。冷进程仍完整读取约 380 MiB；冷启动阻塞与耗时未解决或实测，不宣称彻底完成。 |
| M8 | 已修复。前缀查询使用线性空间索引，由语言包工厂共享；保留长 shortcut、排名与去重语义，避免为每个前缀建立集合。 | `PackPrefixCandidates.kt`、`LanguagePackEngine.kt`；`PackPrefixCandidatesTest` 包含 50,000 条长 shortcut。未测低内存设备峰值。 |

## 低优先级

| 编号 | 处置与当前行为 | 实现 / 回归证据 |
| --- | --- | --- |
| L1 | 已修复。普通组合与 AI 草稿共享中文编码判断；草稿捕获九键／双拼配置，预热与降级均保留原始编码。九键降级保留数字并使用公开读音数字别名。 | `ChinesePreeditCode.kt`、`LocalDraftInput.kt`、`FallbackPinyinEngine.kt`、`ZeroInputView.kt`；`ChinesePreeditCodeTest`、`FallbackPinyinEngineTest` 通过；`LocalDraftInputTest` 九键与双拼用例仅编译。 |
| L2 | 已修复。词库容量与取消独立分类，不再登记为写盘故障或错误提示；真实写入失败仍可见。 | `UserLexiconRepository.kt`；`UserLexiconCapacityTest`、`UserLexiconRepositoryTest`。 |
| L3 | 已修复整份明文 String 解码。词库与 emoji 使用可清理缓冲区上的 JSON reader。 | `UserLexiconFormat.kt`、`EmojiHistoryFormat.kt`；`EncryptedJsonBoundaryTest`、用户数据测试通过。模型字段仍是 JVM String，不声称所有副本可擦除。 |
| L4 | 已修复。Keystore 查询异常向上传播，不伪装成密钥不存在。 | `AesGcmKeyStore.kt`；`AesGcmKeyStoreTest` 验证 provider 故障。 |
| L5 | 已修复。第 33 个保存会话显式容量失败，保留已有会话，不再自动淘汰最旧记录。 | `AiConversationRepository.kt`；`AiRepositoryTest`。 |
| L6 | 已修复。工作台策略使用当前编辑器实际隐私；无建议普通文本仍允许隔离草稿，但不放宽其他敏感类别。 | `AiEditorPolicy.kt`、`AiWorkbenchController.kt`、服务接线；`AiWorkbenchControllerTest`、AI policy 测试。 |
| L7 | 已修复。导入 inbox 在接受回调成功后才消费，预算或权限拒绝保留原始到期时间。 | `AiContentInbox.claimIfAccepted` 与服务接线；`AiContentInboxTest`。 |
| L8 | 已修复。递归 JSON 解析前拒绝超过 32 层嵌套，非流式与 SSE 使用同一检查，失败正常终止请求。 | `AiJsonResponse.kt`、响应 reader；`AiResponseReaderTest`、`AiTransportProtocolTest` 覆盖深层 JSON 与终态。 |
| L9 | 已修复。公开目录最多 1024 行；管理页按 64 行分页构建视图。 | `DictionaryCatalog.kt`、`DictionaryManagerActivity.kt`；`DictionaryCatalogTest` 覆盖上限。设备滚动体验未验收。 |
| L10 | 已修复。损坏的语言包启用状态在扫描和后续开关操作中一致失败关闭，不能重新启用其他包。 | `LanguagePackEnabledState.kt`、`LanguagePackInstaller.kt`；`LanguagePackEnabledStateTest`。 |
| L11 | 已修复。请求队列／deadline 槽位耗尽报告独立 CAPACITY，使用中英文容量提示。 | AI provider、`AiErrorMessages.kt` 与两组 strings；协议和 provider 单元测试。 |
| L12 | 已修复。平台编辑器 RuntimeException 被收口；不确定是否已执行的修改不重放；批次结束在 finally 中执行，并使上下文／重选状态失效。 | `AndroidEditorConnection.kt`；`AndroidEditorConnectionFailureTest` 已编译，未在设备执行。 |
| L13 | 已修复。语言语义使用 `KeyboardLanguage`，不再比较可见标签；标签资源化，中文语言包保留分节符。 | `KeyboardLanguage.kt`、`KeyboardLayouts.kt`、UI 接线与双语资源；`KeyboardLanguageTest`。 |
| L14 | 已修复。长按备选浮层在 detach 和取消时关闭。 | `KeyboardPanel.kt`；UI 编译和 Lint 通过；需设备验证窗口消失。 |
| L15 | 已修复 Activity 重建丢结果。应用级有界删除协调器保留结果，页面恢复后交付，避免持有旧 Activity。 | `SettingsDeletionCoordinator.kt`、`MainActivity.kt`、`AppGraph.kt`；`SettingsDeletionCoordinatorTest` 覆盖重建、失败和重复完成。结果只在进程内保留，非持久删除回执。 |
| L16 | 已修复。管理页取消额外认证后清空元数据并结束页面。 | `SecureClipboardManagerActivity.kt`；`SecureClipboardManagerPrivacyTest` 已编译，未运行。 |
| L17 | 已修复两个路径。防护关闭取消过期排队工作，为串行注销保留槽位；本地面板任务拒绝清空旧缓存并显示可重试错误。 | `ClipboardGuardRuntime.kt`、`ZeroInputService.kt`；`ClipboardGuardRuntimeTest` 平台用例已编译。本地面板拒绝路径仍需手工注入验收。 |
| L18 | 已加固。检查 Debug/Release 合并清单的备份、Release 调试标记、四个导出组件、服务保护权限与意外隐式导出；校验全部备份排除域。 | 根 `build.gradle.kts`；`testPrivacyBoundary` 负例自检随 `privacyCheck` 通过。合并清单路径跟随 app 实际 buildDirectory。 |
| L19 | 已加固。只允许审核过的隐私配置路径，拒绝新资源限定符／source set 覆盖；禁止无障碍键事件过滤；平台节点测试仅精确路径白名单。 | 根 `build.gradle.kts`；未知限定符、过滤标志等负例自检通过。 |
| L20 | 已加固。Wrapper 发行包 SHA 固定；Linux CPython 3.11 CPU wheels 的直接／传递依赖全部固定哈希；已有源码包和源码树重新校验。发布使用 main 可达完整 SHA，构建只读、发布独立写权限，不保留 checkout 凭据。 | Wrapper、`publish-resources.yml`、`requirements-linux-cpu.txt`、`pinned-source-archive.ps1`；源码归档安装与 4 项完整性检查通过。Linux wheel 安装及 GitHub Actions 尚未实际运行。ADR 0021 同步为先审核合并、再显式发布。 |

## 性能与观察

| 编号 | 处置 | 验证与限制 |
| --- | --- | --- |
| P1 | 已修复。转入 native 翻页时重置个人页游标；下一页能力合并 native 与个人来源。 | `PersonalCandidatePagingTest` 覆盖个人页耗尽后 native 继续翻页；循环有上限，防止测试挂起。 |
| P2 | 保留观察，不引入额外缓存或可变容器复用。候选 map/list 与策略对象分配规模有界，当前没有分配采样证明其为瓶颈。 | 待 Perfetto／分配分析；不宣称完成性能优化。 |
| P3 | 已修复相同模型的重复刷新；展开候选跳过不必要测量与布局请求。变化后的有界列表仍可重建。 | `AiContextView.kt`、`AiConversationListView.kt`、`SecureClipboardPanelView.kt`、`ExpandedCandidatesView.kt`；UI 测试与 Lint 通过，未测帧耗时。 |
| P4 | 已修复。复用滑行 Path，仅在轨迹可见或需要擦除时触发轨迹重绘。 | `GlideTouchTracker.kt`、`KeyboardPanel.kt`；单元测试和构建通过，60/120 Hz 设备表现未测。 |
| P5 | **待设备确认**。静态不能确定 IME 窗口是否已处理系统栏，直接叠加 inset 可能重复留白；保留现有实现。 | 当前无设备。需手势／三键导航 × 停靠／单手 × 横竖屏 × 深浅主题，确认底排触控与导航条位置后再修复。 |
| P6 | 已修复。读音匹配用 regionMatches 避免循环 substring；学习编码只在实际产生提交时计算，候选选择保留原有学习语义。 | `DictionaryEditReading.kt`、`RimeInputEngine.kt`；Rime 引擎测试与真实源码三 ABI 构建通过。未做分配基准。 |

另清除未使用的 `engineReloadPending`，修正 `registerInteraction` KDoc；Android 回归测试改用语义化语言 setter。
当前服务约 1850 行，属于既有复杂生命周期编排，低于复杂文件 2500 行上限；本轮未机械拆分服务。

## 实际验证

环境：Windows / JDK 17 / Gradle Wrapper 8.14，使用现有 Android SDK、NDK 与固定 Rime 源码。Gradle 串行执行。

普通 `app/build` 下 AAPT 写 `stableIds.txt` 仍报“数据无效 (13)”。相同工具写临时目录成功；
本次用忽略目录下的 init script 仅将 app 输出重定向至 `.tmp-evidence/app-build` 完成验证。
没有把环境问题描述成已修复，也未绕过资源编译、测试或安全检查。

```powershell
$env:JAVA_HOME='D:\env\jdk17'
$env:GRADLE_USER_HOME='L:\zeroInput\.gradle-user'
# .tmp-evidence/review-build-path.init.gradle 内容：
# gradle.beforeProject { project ->
#     if (project.path == ':app') {
#         project.layout.buildDirectory.set(new File(project.rootDir, '.tmp-evidence/app-build'))
#     }
# }
./gradlew.bat :app:assembleDebug testDebugUnitTest :engine-api:test :engine-english:test `
  :engine-dictionary:test :ai-api:test privacyCheck :app:lintDebug `
  :app:compileDebugAndroidTestKotlin '-Pandroid.injected.build.abi=x86_64' `
  -PrequireRime=true --no-parallel -I .tmp-evidence/review-build-path.init.gradle
# BUILD SUCCESSFUL，35s，446 tasks
./gradlew.bat :app:assembleRelease privacyCheck -PrequireRime=true --no-parallel `
  -I .tmp-evidence/review-build-path.init.gradle
# BUILD SUCCESSFUL，1m32s，355 tasks；三 ABI
D:/env/python3/python.exe -m unittest discover -s tools/resources -p 'test*.py'
# 3 tests，OK
./tools/test-pinned-source-archive.ps1
# 固定归档安装及四项完整性检查通过
```

Debug XML 单元测试统计（JVM 模块读取 `test`，Android 模块只读取 `testDebugUnitTest`，不重复累计旧 Release XML）：

| 模块 | tests | failures/errors | skipped |
| --- | ---: | ---: | ---: |
| app | 281 | 0 | 2 |
| ime-core | 142 | 0 | 0 |
| user-data | 70 | 0 | 0 |
| engine-rime | 66 | 0 | 0 |
| engine-english | 12 | 0 | 0 |
| language-pack | 54 | 0 | 0 |
| model-scoring | 20 | 0 | 0 |
| security | 11 | 0 | 0 |
| ime-ui | 47 | 0 | 0 |
| engine-dictionary | 30 | 0 | 0 |
| engine-api | 5 | 0 | 0 |
| ai-api | 14 | 0 | 0 |
| 合计 | **752** | **0** | **2** |

证据：[完整检查日志](.tmp-evidence/review-full-relocated.log)、[Release 日志](.tmp-evidence/review-release.log)、
[精确测试统计](.tmp-evidence/review-test-counts.json)、[APK 包名／ABI／权限／签名／摘要](.tmp-evidence/review-apk-checks.json)。

`verify-apk.py full` 已分别通过 Debug x86_64 和 Release 三 ABI 产物，检查全部 XZ 词表哈希、
ONNX native 哈希、3972 个 emoji、Mini 包体及 ZIP 冗余空间。`aapt2 dump badging` 检查包名、
权限和单 ABI；ZIP 中每包存在对应 ABI 的 `libzeroinput_rime.so`。
Debug 通过 apksigner 验证且可调试；Release 三包均未签名且不可调试，不能作为正式可发布产物。

| 产物 | 字节 | SHA-256 |
| --- | ---: | --- |
| `.tmp-evidence/app-build/intermediates/apk/debug/app-x86_64-debug.apk` | 65080782 | `806160b73b63c17a9c6af7545dbb84584d6c393fbd2a25843ef27aa975dda123` |
| `.tmp-evidence/app-build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk` | 57138415 | `29308ebf83e5d0fe4d0c819ffb2b5ea607512a223caab45db8e32ae8981cc5ae` |
| `.tmp-evidence/app-build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk` | 56477175 | `9245c9a564c32cdfe7bb4c5f6bfef13b61c2df95ac4d18829041353ce30c155c` |
| `.tmp-evidence/app-build/outputs/apk/release/app-x86_64-release-unsigned.apk` | 57354911 | `6d9374d98c79206ebf39d3d4ade6584b4193be00e5f5c3dd141ed07ad6dce28d` |

## 未运行项与验收步骤

- 无设备连接，所有 Android 平台用例仅编译；未运行 androidTest、认证页面、View 旋转／浮层、真实 InputConnection、JNI 异常注入或导航栏矩阵。P5 保持待确认。
- 九键输入 `64`、微软双拼含 `;` 的组合转入 AI，待引擎就绪后检查候选、选词与原始编码；退出 AI 和切换编辑器后旧结果不得提交。
- 管理页追加认证后点击取消，应结束页面且不留元数据；删除处理中旋转／返回设置应显示一次最终结果。进程终止不保证结果提示仍在。
- 注入本地面板执行器拒绝，应清空旧个人／最近／剪贴板缓存、显示失败并允许后续刷新；防护队列满时关闭监听应最终注销。
- 未联调真实 AI 端点：需主动测试 Anthropic 深度思考、取消与流终态；本地编码／模拟传输测试不能代表厂商裁决。
- 未测冷启动 380 MiB 校验耗时、低内存设备峰值、键盘帧耗时与 GC；M7/P2/P3/P4 不报告量化收益。
- 未执行实际 GitHub 发布、Linux wheel 哈希安装或新的无 third_party 检出；供应链改动的静态校验与本地脚本测试不替代这些环境验收。
- 未运行 `tools/package-test-apk.ps1`，其默认输出路径与本机 AAPT 问题冲突；已单独做上述测试、隐私、Lint、构建和 APK 校验，不宣称包装脚本通过。

架构与边界说明同步于 [architecture](docs/architecture.md)、[threat model](docs/threat-model.md)、
[SECURITY](SECURITY.md)、[ADR 0021](docs/adr/0021-optional-public-resources.md) 和 [README](README.md)。
