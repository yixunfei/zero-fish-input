# Rime 偶发就绪超时：根因分析

日期：2026-10-24
基线：`main` @ `e0a9cb81f06291a004be9de7845e7b06cdd84201`。
范围：分析 `AiImeInteractionTest.typingBeforeDraftWarmupStillConvertsChineseAndLeavesTheHostUntouched` 的 60 秒 Rime 就绪超时；未修改生产源码、测试、等待时间或词典资产，未执行提交。
方法：按 systematic-debugging 分离可复现机制、原事故未保留的信息与静态生命周期风险。

## 结论

**已复现并量化的主要直接机制：完整公共词表的冷 native 编译时间超过该交互用例的 60 秒上限。** JNI 初始化等待 librime maintenance 完成，而 maintenance 正在实际编译词典；未被打断的正常初始化在此期间保持 `INITIALIZING`。这不是已证实的死锁、主线程 ANR 或中文转换错误。

同一 APK、同一设备上的 `CompressedDeploymentTest` 测得：

| 路径 | 实测 |
| --- | ---: |
| 新目录，资产部署 + native 初始化 + 首次转换验证 | **71,046 ms** |
| 关闭后复用同一代产物 | **1,243 ms** |
| 测试总耗时 | 77,252 ms，1/1 通过 |

冷路径已经超过 `AiImeInteractionTest.kt:31-36` 的 **60,000 ms** 上限。后续是否达到 `READY`、是否恰逢已有可用产物、何时被前序测试或会话生命周期切换 generation，决定该交互用例是否暴露超时。这解释了偶发性及“单项通过不能消除全量失败”。

**证据边界：**原全量失败现场的 `state`、`initializationError`、generation 和 native 栈已被后续运行覆盖。因此本报告证明了一条足以产生该失败的、在当前构建中可重复的因果链；没有声称已唯一还原原事故全过程。原事故的因果状态是 **deepest-confirmed-root-unknown**，置信度 **B**：直接编译等待已证实，触发它的上游 generation／生命周期原因尚未闭合。

## 完整因果链

1. **APK 只有源表，没有预编译 table。** 精确测试 APK 的 `assets/rime` 下 `.bin` 数量为 0，包含 16 个压缩源表和一份清单；`engine-rime/build.gradle.kts:21-49,142-145` 仅打包压缩表及基础配置。16 张表解压总量为 70,601,147 字节。
2. **首次启动或新 generation 没有可复用的编译产物。** `RimeAssetInstaller.kt:67-74,102-110` 将版本、词典、语法模型及启用状态纳入 generation，并使用 `shared-<version>` / `user-<version>`。librime 的 staging 目录默认为 `user_data_dir/build`；该目录的 table 不存在时触发重建。
3. **初始化主动等待 maintenance。** `zero_rime_jni.cpp:154-207` 串行执行 native 初始化，`start_maintenance(true)` 后调用 `join_maintenance_thread()`；`deployer.cc:105-147` 的 maintenance 在独立 native 子线程运行，join 调用 `work_.get()`。
4. **实际子线程工作是全量词典编译。** 线程栈符号化到 `DictCompiler.BuildTable()`、`Table.Build()`、`StringTableBuilder.Build()` 和 marisa trie 构建；`deployment_tasks.cc:324-376` 的 schema 更新调用 `DictCompiler.Compile()`。`DictCompiler.Compile()` 按源表与 schema 校验和判断重建，不是同内容重启后无条件重编译。
5. **Kotlin 只有在 native 验证及转换验证完成后才发布 READY。** `RimeRuntime.kt:110-180` 在 native 工作外持有初始化所有权；`verifyDirectories()` 在 `:183-192` 调用 JNI 并验证会话。因此 60 秒等待观察到的是尚未就绪，不能单独说明哪一个内部阶段。
6. **同一 worker 无法运行新的准备任务。** engine worker 是有界单线程；正在执行的 `nativeInitialize()` 不被 Kotlin 任务取消打断。排队的 warmup 可被取消，但正在运行的 native 初始化仍由该 worker 持有到返回。

## 决定性证据

- 精确安装 APK 与本地 `app-x86_64-debug.apk` 的 SHA-256 均为 `3496d86baae6db49efac7401b84f7f945498629165738325e17c3684a69477ab`；native `BuildId` 为 `e1400c5f5e5d977b4110d36ae0185acbb32298b8`。
- `zeroinput-engine-worker` 的 Java 栈：`prepareDictionariesIfIdle → initialize → verifyDirectories → NativeRimeBridge.nativeInitialize`；native 栈为 condition-variable wait → `std::future<void>::get()` → `RimeJoinMaintenanceThread()` / `Deployer::JoinWorkThread()` → JNI initialize。
- 同时另一个 `zeroinput-engin` native 线程处于运行态，符号化为实际 table/trie 构建，而非 Kotlin monitor 或空 executor 等待。较早栈也符号化到 `EntryCollector::CreateEntry()`，覆盖词典收集阶段。
- 一次词典恢复序列最终生成了 74,179,788 字节的 `zeroinput_public.table.bin`，同时有 22,192 字节 prism 和 263,796 字节 reverse 文件。
- `PublicDictionaryPlatformTest#installedPublicWordsEnterNativeCandidatesAndCanBeRemoved` 最终 110.259 秒通过，证明长 native 工作可以完成；通过本身不证明耗时低于交互预算。
- 同一 AI 测试方法在诊断中两次复现 60 秒断言失败，instrumentation 总时间约 72.578 和 66.05 秒；随后两次通过分别约 17.825 和 16.568 秒。原顺序的 `AiImeInteractionTest` 另一次为 5/5、56.16 秒通过。两组结果均不能单独代表冷启动或修复验证。

原始线程采样来自运行时 `debuggerd`，未修改应用二进制。显式采样产生的 trace 不是 ANR 证据；本诊断未观察到崩溃或 ANR。

## 候选机制分级

| 候选 | 判断 | 证据或缺口 |
| --- | --- | --- |
| 新安装、数据清除或新 generation 的全量编译 | **已复现的直接机制** | 冷 71.046 秒 / 热 1.243 秒；JNI join 与编译子线程栈吻合。 |
| 同内容、同 generation 仍重复全量编译 | **不支持** | 热复用实测 1.243 秒；checksum 和已验证 generation 有复用路径。 |
| 词典变更后的 generation 重建 | **已复现同类机制** | 词典测试的恢复路径出现新 user generation 和 BuildTable。共享 native runtime 的测试需要成对看，不能把单独冷 fixture 时间冒充交互用例时间。 |
| worker 空闲、任务完全未启动或 Kotlin 锁自死锁 | **与采样不符** | worker 正在 JNI 内等待真实 native 子线程。 |
| 主线程阻塞或 ANR | **未证实** | 等待属于专用 engine worker；没有 ANR/crash。 |
| AI 插入、中文转换或草稿 worker 逻辑 | **不是该断言的直接原因** | 断言在草稿 worker 被人为阻塞及后续转换／AI 断言之前。 |
| 模拟器 CPU、I/O、内存压力 | **可能的放大器** | native CPU 和高 RSS 仅说明资源成本；没有同硬件的受控基线，未单独归责。 |
| `FAILED`、活动会话或 close/retry 交错 | **原事故未分辨** | 原失败缺状态与错误快照；下列静态风险需要独立测试。 |

原全量运行中的 AI 用例早于词典平台测试，后者不能被倒因为原事故的先行触发器。聚焦测试与整套测试的 Application、native runtime、缓存 generation 和会话状态必须分别记录。

### 因果闭合检查

- **直接机制的链：** APK 无预编译 table → 新 user generation 无 build → maintenance 编译 → JNI join → runtime 延后 READY → 60 秒断言超时。每条边均有源码、栈或计时证据。
- **反证检查：**若只是已有产物未被复用，冷／热差异应消失且热路径仍执行 BuildTable；实测热复用为 1.243 秒，栈中的编译属于新目录路径。若只是词典恢复后的交叉污染，冷 fixture 不应独立超过 60 秒；实测冷 fixture 为 71.046 秒。这排除了两个替代解释，但没有排除原事故中的 `FAILED` 或 close 后未恢复。
- **最强反驳：**71 秒来自独立 fixture，而捕获的编译栈来自词典测试，不是原 AI 断言失败那一刻。因此它证明充分机制，不构成原事故唯一根因；这也是保留 B 置信度而不升级为 A 的原因。
- **拓扑：**同一 60 秒症状是 `disjunctive-or`：冷／重建编译、持久 `FAILED`、活动会话占用或 close/retry 交错均可令 isReady 不成立。没有同一事故中多个独立根因同时活动的证据，不标记为 compound。
- **上游边界：**编译产物与 ABI／librime 构建的兼容策略尚未测量，原始测试的精确 generation 历史已缺失；把责任继续上移为“构建设计”或“某个前序测试”都会超出证据。当前停点是最深已证实机制，不是完整 recurrence closure。

## 独立生命周期风险

这些是从源码确定的状态条件，不是本次已捕获 native 编译栈的替代根因。当前 JVM 源码树没有 `RimeRuntime` 的直接单元测试，设备用例覆盖成功路径较多。

### 1. close 后最后一个引擎释放，未独立完成 native finalize

`RimeRuntime.close()` 在活动引擎或初始化存在时只设置 `finalizeWhenIdle=true`，将状态置为 `NOT_INITIALIZED`（`:305-322`）。`releaseEngine()` 在计数归零时清除该标志并 finalize（`:270-276`），但 `initialize()` 的守卫仍包含 `nativeInitialized`（`:110-120`）。

因此 `close()` → 最后引擎 `close()` → 新的 `initialize()` 可在没有新会话占用的状态下返回 `false`，直到下一次显式 `close()` 清除 native 状态。真实 `RimeReadinessTest` 的 fixture 在最终 close 后没有活动引擎，所以不构成该路径的反证。需要构造活动会话跨 close 保留的设备回归测试来确认实际暴露。

### 2. 初始化期间重入 prepare 可能使自己的代次失效

`prepareDictionariesIfIdle()` 只在进入前检查 `initializationInFlightGeneration`，`initialize()` 返回后没有原子地消费检查结果（`:30-56`）。如果在 `resolveAssetDirectories()` 期间初始化开始，其第二次锁检查也没有复查 dictionary revision 或 READY 状态；随后可能 `close()` 正在进行的初始化并再次 `initialize()`。旧初始化随后按 generation 失效，重复 native 工作。

这是潜在放大器，不是“每次 prepare 都改变 generation”。需要在准备资产与 native 初始化之间做确定性交错测试；尚无运行时证据把它连接到原事故。

### 3. 状态失败与草稿重试预算不是同一概念

`LocalDraftInput.kt:179-215` 第一次准备使用 `retryInitialization=false`，之后的准备由最多三次 unavailable 重试驱动。若 runtime 已是 `FAILED` 且第一次准备被其他会话占用，`prepareDictionariesIfIdle()` 可能返回而不真正初始化；后续 warmup 也会因 `retryInitialization=false` 跳过 `warmUp()`，消耗一次机会。该条件可延长降级，但尚无原事故现场的重试计数证明。

不建议把未经测量的持续初始化笼统表述为永久死锁，也不建议把上述静态路径合并成单一原因。

## 修复优先级

1. **先消除冷路径的完整编译，而非单纯延长 60 秒。** 评估在受控词典构建阶段生成默认配置的 table/prism 并随资产发布，记录源表、schema、librime/marisa 构建版本及 ABI 的兼容性；安装时校验，损坏或不匹配才后台重建。增量词典仍需明确重建成本。
2. **若预编译方案体积或兼容性不合适，测量并拆解冷部署阶段。** 分离 XZ 解压、校验和、EntryCollector、table/prism 构建与 session 验证。保持 worker 执行和 READY 的转换验证，不把初始化移到主线程，不把未验证的 table 发布为可用。
3. **generation 保留与回滚需要一起设计。** `RimeRuntime.kt:175-176` 成功后清理非当前 generation；最后验证标记和目录保留是重试／回滚依据。不能在未评估磁盘预算与故障回滚前简单改成永久缓存。
4. **独立修复生命周期所有权。** 覆盖 close 时活动会话释放后立即可重新初始化、初始化期间 prepare 重入、FAILED 后恢复、worker 拒绝与取消、native 失败降级。
5. **观测失败现场。** 测试失败时记录 `state`、脱敏的公开错误类别、generation、阶段耗时和是否存在编译产物；需要 native 栈时在模拟器采样。不要把个人输入、候选或正文加入日志。

不建议仅调大断言、仅取消 warmup、仅删除 maintenance join，或用更多并发 native 初始化争抢同一进程级 runtime。这些做法分别掩盖预算、延后必要工作、提前发布未验证状态或扩大锁与生命周期风险。

## 后续验证方案

- 固定 APK 与 ABI，分别测空数据、新 generation、已验证 generation 回退及热复用；保存每阶段耗时和首次真实转换结果。当前 71/1.2 秒只是 API 36 x86_64 userdebug 模拟器的单点数据，不是真机预算。
- 在原始整套顺序重跑 AI 用例，失败即保存状态与 native 栈；区分 `INITIALIZING`、`FAILED` 和 close 后未恢复。聚焦用例可另用独立应用数据／冷进程减少历史 generation 干扰；默认 instrumentation 的独立进程并不清除既有持久缓存，不能把进程隔离等同于冷数据。
- 保留现有 16 张源表、中文转换、词典安装／删除／恢复、语法资源、双拼与九键 schema 的行为测试；任何 native/资产修复按工程规则补三 ABI 与 `-PrequireRime=true`。
- 验收同时包含冷启动预算、热复用预算、编译失败降级和最后一次 `close()` 后的独立恢复。单次绿测不能作为偶发问题已解决的证据。

## 本次诊断边界

设备：API 36、x86_64、userdebug 模拟器 `emulator-5554`。当前构建有真实 Rime；本轮 ADB 诊断没有追加 `-PrequireRime=true`，不将其表述为 requireRime 正式验收。未重新跑整套 404 项设备套件、三 ABI 或真机测试。

未修改生产源码、测试或既有 `docs/code-audit-recheck-2026-10-24.md`；本文件是新增诊断记录，未创建提交。所有本轮启动的 instrumentation 后台任务均已结束并收集结果。安装 APK 时曾调整模拟器 ADB root 以读取线程栈，未修改应用代码。

Aegis 收口：核心因果链为**已测量／已观察**；原事故完整状态为**未知**；生命周期风险为**源码推断、待交错测试**。避免把测试超时扩大成转换回归或用局部通过掩盖全量失败。基线与架构边界保持，未引入新的运行时分支或兼容层。下一项最高价值验证是失败时的状态快照与原顺序 native 采样。
