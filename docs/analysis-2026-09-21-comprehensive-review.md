# zero finish input 全面代码与配置分析报告

- 分析日期：2026-09-21
- 分析方式：只读静态分析（文档 + 11 个模块全部主源码 + 测试与构建配置），未做运行期插桩
- 项目版本：v0.2.0 预发布测试版（Debug 签名），应用 ID `dev.zeroinput.ime`
- 分析范围：`app / engine-api / engine-rime / engine-english / engine-dictionary / ime-core / ime-ui / model-scoring / language-pack / security / user-data` + 根构建配置

## 2026-09-21 实施状态与勘误

本表描述当前源码。后文保留原分析结构并更正关键错误；未重新核验的规模、行号和第三方
评分仅作原始静态基线，不能代替当前构建结果或同条件实测，也不代表公开 APK 已更新。

| 事项 | 当前状态 | 验证或剩余缺口 |
| --- | --- | --- |
| 中英文上屏后联想 | 已扩充到 1,698 组自编公开词对、723 个语言/前缀键；最多 8 项、仅点选提交 | [效果评估](word-association-quality.md)：开发样例首选命中 36/136 → 136/136，负向误触发 4/20 → 0/20；222 项单元测试及 8 项设备回归通过，独立语料及 ARM 验收待补 |
| Rime secondary 会话 | 已按需后台创建，过期结果释放 | [性能实施记录](../performance-analysis.md)；会话数不能推导总内存翻倍，仍缺真机测量 |
| 热路径优化 | 设置镜像、英文循环与 scratch 容器、键盘/emoji 颜色缓存、Enter 局部重绑已实施 | 收益需在目标设备测量，不能沿用静态估算百分比 |
| 语言包清单边界 | 导入和已安装包发现均已限制实际字节并严格解码 UTF-8 | 保持纯数据、失败关闭和校验后激活 |
| 安全存储可靠性 | 修复丢失密钥读取、原子文件失败漏报、清除失败、索引失效与清除并发问题 | [安全存储验证](security-storage-validation.md)：216 项单元测试、44 项设备测试通过 |
| 安全测试缺口 | 新增 10 项 security JVM 测试及 13 项存储/保险库设备回归 | 原有加密词库、表情和保险库 Android 测试并非缺失；真机认证及密钥永久失效仍待验收 |
| Keystore 认证绑定 | 本轮未实施；沿用现有应用层认证及存储格式 | 需独立设计密钥用途、认证交互及已有数据处置，不能自动重生成丢失密钥 |
| 英文词表与纠错 | 仍待改进 | 前缀查询的分配优化不等于词表、纠错或翻页能力已补齐 |

存储可靠性修复保持 AES-GCM envelope format 1、保险库 JSON 格式和认证交互不变。正文与索引仍是
两个独立原子文件，失败状态只在进程内保留；未实现跨文件事务或断电恢复保证。
凭据返回用例因缺少测试 PIN 跳过，未计入上述 44 项通过数；API 26、ARM 真机、真实
生物识别及 Release 构建不在该轮验证结果中。联想扩充的 8 项设备回归另行记录，不与上述
存储验收数量相加；开发评测样例参与了词表调整，不能作为独立准确率证据。

原报告把短词模型的 1.06% 误改率归给拼音纠错，现已分开；luna essay 是词频表，不能
直接当作连续句子训练二元模型。“可选明文片段历史”违反现有隐私边界，已撤回该建议。

---

## 第一部分：结论摘要

**当前结论：离线与隐私边界已有明确实现和回归测试；基础词联想及安全存储修复已落地，英文能力、联想覆盖和真机证据仍是主要缺口。**

1. **隐私边界有构建与测试约束。** 不声明 `INTERNET`，`privacyCheck` 校验权限与组件；个人数据使用隔离 Keystore 密钥和 AES-GCM，安全剪贴板正文/索引分离，备份禁用。这些证据不构成行业安全排名。
2. **输入能力仍需扩充。** 整句、简拼、模糊音和基础词联想已具备；英文仍是小词表前缀匹配。拼音纠错和两字词模型重排是不同实验项，均默认关闭；1.06% 误改率来自后者的留出集。
3. **性能需要真机证据。** Rime secondary 已按需创建；共享运行时和词典存在，不能从两个会话推导总内存翻倍。线程与延迟优化需测量后决定，模拟器不能替代 ARM 验收。
4. **功能范围有缺口也有明确取舍。** 滑行、快捷短语面板、单手/浮动键盘等尚待推进；联网同步和系统剪贴板正文采集不属于允许扩展方向。
5. **安全测试已扩展。** 存储可靠性验证完成 216 项单元测试及 44 项设备回归，其中 security 新增 10 项 JVM 测试；联想扩充后当前单元测试总数为 222 项。不能据此宣称真实生物识别、所有 Android 版本或崩溃恢复已验证。

### 优先级清单（总览，细节见第四、五部分）

| 级别 | 事项 | 性质 |
| --- | --- | --- |
| **P0** | ARM 真机输入延迟与内存实测验收缺失，发布无性能证据 | 已实现的验收缺口 |
| **P1** | 联想已扩充并建立固定样例评估，仍缺独立真实语料评测 | 能力与验证缺口 |
| **P1** | 拼音纠错缺少独立效果评测；模型重排误改率超门槛 | 两项实验分别验收 |
| **P0** | 词库仅为 luna_pinyin 基础静态词库，无新词/热词更新通道 | 缺失能力 |
| **P1** | 英文引擎约 179 词、纯前缀、无纠错、无翻页 | 已实现但过弱 |
| 已实施 | 全拼 Rime secondary 按需创建 | 真机内存收益待测 |
| 待设计 | Keystore 认证绑定及已有密钥/数据处置 | 不属于本轮授权范围 |
| 已实施 | security JVM 测试与保险库存储/清除负向回归 | 物理设备及失效密钥验收待补 |
| **P1** | 无滑行输入；快捷短语/常用语无面板入口 | 缺失功能 |
| **P1** | 无障碍仅有 contentDescription，无语音公告与候选播报 | 缺失功能 |
| **P2** | emoji 库约 300 条，远小于 Unicode 全集 | 已实现但量少 |
| **P2** | 常驻 7-8 个线程可合并；热路径仍有冗余分配 | 已实现待改进 |
| **P2** | 无单手/浮动键盘、按键音、按键弹泡、主题自定义 | 缺失功能 |
| **P2** | iQOO 厂商剪贴板历史清理未实现 | 已知未竟事项 |

---

## 第二部分：项目现状梳理

### 2.1 模块划分与职责

11 个 Gradle 模块（`settings.gradle.kts`），依赖方向严格单向（`docs/architecture.md`）：

| 模块 | 职责 | 规模与关键类 |
| --- | --- | --- |
| `app` | Android 组件、组合根、生命周期编排、设置、平台适配 | `ZeroInputService.kt`（1209 行）；11 个 Manifest 组件全部默认非导出 |
| `engine-api` | 稳定引擎/个性化端口与不可变模型 | `InputEngine`、`PersonalizationStore`、`ChineseInputOptions`、`EngineCapability`（8 项能力枚举） |
| `engine-rime` | librime 1.13.1 JNI 适配、中文全拼/九键、降级引擎 | `NativeRimeBridge.kt`（17 个 external）、`zero_rime_jni.cpp`（405 行）、`RimeRuntime`、`RimeInputEngine`、`ExpandingRimeEngine` |
| `engine-english` | 离线英文候选 | `DefaultEnglishLexicon.kt`（约 179 词）、`EnglishInputEngine.kt` |
| `engine-dictionary` | 离线参考词典对照引擎 | `reference-pinyin.tsv`（132 行、上限 512 条） |
| `ime-core` | 会话状态机、隐私策略、候选窗口、模型排序策略 | `InputSessionController.kt`（673 行）、`EditorPrivacyPolicy.kt`、`CandidateWindow`、`PersonalCandidatePaging`、`AsyncCandidateRanker` |
| `ime-ui` | 键盘/候选/emoji/安全剪贴板面板 | 26 个 Kotlin 文件，`ZeroInputView.kt`（477 行）、`EmojiCatalog`+`AdditionalEmoji`+`KaomojiCatalog`（约 300 条） |
| `model-scoring` | 可选 ONNX Mini INT8 候选评分 | `RobertaMiniScorer.kt`、`MiniModelAssets.kt`（SHA-256 钉死） |
| `language-pack` | 纯数据语言包解析/校验/安装/注册 | `LanguagePackParser.kt`、`LanguagePackInstaller.kt`、`PackPathPolicy.kt` |
| `security` | Keystore、AES-GCM、认证授权原语 | `AesGcmKeyStore`、`EncryptedFileStore`；内部 `AesGcmEnvelope` 和 `AuthenticationLifetime` 支持 JVM 负向测试 |
| `user-data` | 加密词库、emoji 历史、表情仓库、安全剪贴板库 | `UserLexiconRepository`、`SecureClipboardVault` 等；保险库管理操作统一校验删除代次 |

**评价：模块边界干净，职责单一，无循环依赖。`QueuedPersonalizationStore`（385 行）放在 `app` 而非 `user-data` 是有意的适配层安排，合理。**

### 2.2 核心输入流程

一次按键的链路（静态追踪，详见 `performance-analysis.md`）：

```
ZeroInputService.handleKeyboardAction
  → registerInteraction（取消粘贴同意/失效重转换）
  → syncSessionPrivacy（EditorPrivacyPolicy 按编辑器分类）
  → InputSessionController.handle(InputCommand)
      → 敏感编辑器：直通降级，不建引擎（L517-537）
      → engine.handle(EngineKey) → RimeInputEngine.readUpdate（JNI 单次上下文读取）
      → apply：提交/组合 → 学习 → publish
  → publish：引擎快照 → CandidateWindow（6 页窗口）→ PersonalCandidatePaging（个人候选叠加）→ 路由重建 → onStateChanged → 渲染
```

关键机制：
- **冷启动不阻塞**：`onStartInput` 先装纯内存降级引擎，Rime/语言包由 `EngineWarmupCoordinator` 在有界单线程队列预热，令牌校验后才交接（`docs/architecture.md` 第 2 节）。
- **未消费键回退**：引擎不认领的键做字符/退格反射校验并回滚原文（`InputSessionController.kt:557-608`）。
- **引擎熔断**：native 初始化失败显式进入 FAILED 并用降级引擎，不 crash、不丢键（`RimeRuntime.kt:188-193`）。
- **逐段选字/撤销/重选**：经 `CompositionEditingEngine`/`ReadingSelectionEngine` 可选端口；`RecentComposition` 仅存一条 ≤128 字草稿，CharArray 用后清零。

### 2.3 词库与语言模型

- **中文**：luna_pinyin（明月拼音），原统计为 70842 行 dict.yaml + essay.txt 约 27.8 万行词频数据，后者不是连续句子语料；整句输入开启（`schema.yaml` `enable_sentence: true`、`express_editor` 分段编辑）；OpenCC 简繁双向，构建期哈希校验；librime 自带用户词典关闭，个人词频只走 ZeroInput 加密 `PersonalizationStore`。
- **英文**：内置约 179 词（`DefaultEnglishLexicon.kt`），纯 `startsWith` 前缀匹配，打分 = 已输入词 > 学习词 > 词表序，无纠错、无翻页。
- **实验模型**：UER RoBERTa-Mini WWM INT8（约 15MB），仅对首页前 8 个候选中读音全匹配的两字词重排，margin ≥ 1.25 才置顶，80ms 截止，默认关闭（`ModelRankingPolicy.kt`、`AsyncCandidateRanker.kt`）。
- **语言包**：纯数据包，仅 zh/en，词典上限 5 万条/32MB，zip/manifest 双重校验（`LanguagePackParser.kt:12-68`、`LanguagePackInstaller.kt:150-226`）。

### 2.4 候选词排序与联想

组合候选由 Rime 静态词频、个人加密词频和可选 ONNX 重排协调。当前另有独立的上屏后词联想端口，使用公开词对和当前会话成功提交的有界上下文，受隐私策略及候选身份校验保护，见 [ADR 0013](adr/0013-offline-word-associations.md)。候选分页和页尾“相近读音”扩展仍属于组合输入能力。

### 2.5 键盘布局与交互

- 全键盘 + 九键（中文）、双符号页、数字/电话/日期专用布局、横屏紧凑布局（4 行压 3 行）；按键高度三档（竖屏 48/52/60dp，横屏 48/48/52）。
- 触控：矩形命中、release 派发、多指针分割、滑出取消、长按退格 65ms 连发先清组合（`KeyboardPanel.kt`、`BackspaceRepeater.kt`）。
- Shift 三态（点按单字母/长按锁定）；回车跟随编辑器 action，`IME_FLAG_NO_ENTER_ACTION` 强制换行。
- 4 主题（经典/极简灰/薄荷绿/樱花粉）+ DayNight；按键震动默认开、按键音关闭。
- **无滑行输入、无按键弹泡预览、无单手/浮动键盘**（代码实证）。

### 2.6 设置项（`SettingsScreenView.kt:140-193` 分组）

- 输入法：系统状态/启用/切换。
- 隐私：学习开关、无痕模式、安全剪贴板、系统剪贴板防护（监听/键盘提醒/通知/悬浮窗/清理方式/清理前认证，全部独立开关默认关闭）。
- 数据：词组管理（增删/JSON 导入导出，2 万条/5MiB 上限）、表情管理、清除个人数据。
- 语言包：导入/启用/删除。
- 输入：按键震动、键盘外观（4 主题 + 3 档高度实时预览）。
- 中文配置：引擎（Rime/离线词典）、全键/九键、简繁、简拼、中文标点、防误触纠错（实验）、候选页大小（5/8/10）、8 组模糊音、短词智能排序（实验）。
- 存储：`SharedPreferences("zeroinput-settings")`。

### 2.7 平台适配

compileSdk/targetSdk 36、minSdk 26（Android 8.0+）；zh-CN/en-US 双子类型（`method.xml`）；DayNight 主题 + 导航栏 insets + `windowLightNavigationBar`；禁用全屏提取；横屏候选/组合并排；硬件键盘不接管按键但强制显示软键盘；三 ABI（arm64-v8a/armeabi-v7a/x86_64）。无障碍：全控件 contentDescription + Shift stateDescription，**无 TalkBack 语音公告**。

### 2.8 当前完成度

- 发布状态：v0.2.0 Debug 签名预发布，Release 未签名（仓库不存私钥，刻意为之）。
- 测试：当前执行结果以开头实施表及各专项验证文档为准，不把历史测试数量或跳过用例当作本轮通过数。
- **未完成**：ARM 真机性能实测、真机生物识别验收、厂商输入框兼容验收、iQOO 剪贴板历史清理、正式发布签名。

---

## 第三部分：原始对标参考（未进行同条件验证）

以下分数保留原报告的主观判断，不是实测、完整安全审计或第三方产品当前状态的证明。
代码状态以实施表为准；本轮未重新评分。联网与系统剪贴板采集等边界不能因对标而放宽。

### 3.1 输入智能

| 能力 | ZeroInput | Gboard | 微软拼音 | 搜狗 | Rime |
| --- | --- | --- | --- | --- | --- |
| 整句输入 | ✅ librime `enable_sentence` | ✅ | ✅ | ✅ | ✅ |
| 纠错 | ⚠️ 实验项默认关闭，仅全键盘，待独立评测 | ✅ 成熟 | ✅ | ✅ | ❌ |
| 模糊音 | ✅ 8 组可配 | ✅ | ✅ | ✅ | ✅（需自配） |
| 语音输入 | ❌ | ✅ | ✅ | ✅ | ❌ |
| 手写输入 | ❌ | ✅ | ✅ | ✅ | ❌ |
| 上屏后联想/下一词预测 | ⚠️ 已有公开有限词对基线 | ✅ | ✅ | ✅ | ⚠️ 弱 |
| 个性化学习 | ✅ 加密词频/词组 | ✅ | ✅ | ✅ | ✅ |
| 语境重排 | ⚠️ 实验项，仅两字词 | ✅ | ✅ | ✅ | ❌ |
| 英文智能 | ❌ 179 词前缀匹配 | ✅ 纠错+滑行 | ✅ | ✅ | ⚠️ |
| **维度评分** | **4.5** | **9.5** | **8.5** | **9** | **6.5** |

### 3.2 性能

| 指标 | ZeroInput | Gboard | 微软拼音 | 搜狗 | Rime |
| --- | --- | --- | --- | --- | --- |
| 冷启动设计 | ✅ 降级引擎先行+后台预热 | ✅ | ✅ | ✅ | ⚠️ 同步初始化 |
| 热路径阻塞 | ✅ 无 I/O/加解密/JNI 初始化 | ✅ | ✅ | ✅ | ✅ |
| 内存 | ⚠️ secondary 已按需创建，ARM 实测缺失 | ✅ | ✅ | ⚠️ 臃肿 | ✅ |
| 包体 | ⚠️ v0.1.0 通用包 28.9MB，+15MB 模型 | ~同类 | 较大 | 大 | 小 |
| 真机实测证据 | ❌ **仅模拟器** | ✅ | ✅ | ✅ | ⚠️ |
| **维度评分** | **6.5**（架构 8.5，证据 4） | **8.5** | **8** | **7** | **8** |

### 3.3 隐私安全

| 指标 | ZeroInput | Gboard | 微软拼音 | 搜狗 | Rime |
| --- | --- | --- | --- | --- | --- |
| 联网行为 | ✅ 无 INTERNET 权限，CI 门禁 | ⚠️ 联网+遥测 | ⚠️ 云候选 | ❌ 大量上传 | ✅ 无网络 |
| 数据收集/上传 | ✅ 零 | ⚠️ 部分 | ⚠️ | ❌ 多 | ✅ 零 |
| 本地加密 | ✅ AES-256-GCM×5 隔离密钥 | ⚠️ | ⚠️ | ⚠️ | ❌ 词典明文 |
| 敏感输入保护 | ✅ 密码/未知/邮箱/URI 分级禁用 | ✅ | ✅ | ⚠️ | ⚠️ |
| 剪贴板 | ✅ 默认不读+私有加密库+认证粘贴 | ⚠️ 历史记录 | ⚠️ | ❌ | ❌ |
| 审计/门禁 | ✅ privacyCheck + 威胁模型 + ADR | 闭源 | 闭源 | 闭源 | ⚠️ 靠社区 |
| **维度评分** | **9.5** | **6** | **5.5** | **3** | **8** |

### 3.4 功能完整度

| 功能 | ZeroInput | Gboard | 微软拼音 | 搜狗 | Rime |
| --- | --- | --- | --- | --- | --- |
| 多语言 | ❌ 仅中/英 | ✅ 900+ | ✅ | ✅ 多 | ✅ 可扩展 |
| 表情符号 | ⚠️ 约 300 条+颜文字 | ✅ 全集+贴纸/GIF | ✅ | ✅ | ⚠️ |
| 剪贴板 | ⚠️ 私有库（无历史面板） | ✅ 历史 | ✅ | ✅ | ❌ |
| 快捷短语 | ⚠️ 有词组管理，无键盘面板 | ✅ | ✅ | ✅ | ✅ |
| 同步/备份 | ❌（刻意） | ✅ | ✅ | ✅ | ⚠️ 手动 |
| 滑行输入 | ❌ | ✅ | ✅ | ✅ | ❌ |
| 单手/浮动键盘 | ❌ | ✅ | ✅ | ✅ | ❌ |
| 主题 | ⚠️ 4 预设 | ✅ 丰富 | ✅ | ✅ 市场 | ✅ 可定制 |
| 无障碍 | ⚠️ 基础 | ✅ | ✅ | ✅ | ⚠️ |
| **维度评分** | **4.5** | **9.5** | **8.5** | **9** | **7** |

### 3.5 综合

| 输入法 | 输入智能 | 性能 | 隐私安全 | 功能完整度 | 定位 |
| --- | --- | --- | --- | --- | --- |
| **ZeroInput v0.2.0** | 4.5 | 6.5 | **9.5** | 4.5 | 隐私优先的离线输入法雏形 |
| Gboard | 9.5 | 8.5 | 6 | 9.5 | 全能标杆 |
| 微软拼音 | 8.5 | 8 | 5.5 | 8.5 | 系统级均衡 |
| 搜狗 | 9 | 7 | 3 | 9 | 智能强、隐私差 |
| Rime | 6.5 | 8 | 8 | 7 | 可定制离线引擎 |

后续应以联想覆盖、纠错独立评测、英文词表与真实设备验证衡量改进，不把主观分数作为验收标准。

---

## 第四部分：缺失功能项（按优先级）

### P0 — 直接决定"智能输入法"名实是否相符

1. **上屏后联想 / 下一词预测 — 基础功能已实施**
   - 当前使用独立端口、有限公开词对和有界内存上下文；默认开启、仅点选提交，敏感输入禁用。
   - 已将 286 组词对扩充到 1,698 组，补充日程、工作、出行等场景；修复单字后缀误触发和连续联想入口缺口。
   - 新增冻结公开构造样例的基线与可重复评估，见[效果评估](word-association-quality.md)。样例用于指导本轮扩充，不能把其通过率当作独立准确率；下一步仍需有明确许可的独立语料与真实设备评估。
   - luna essay 是词频表，不能直接训练依赖连续上下文的二元模型。
   - 相关设计与验收见[词联想验证](word-association-validation.md)及 [ADR 0013](adr/0013-offline-word-associations.md)。

2. **拼音纠错困在实验状态**
   - 影响范围：全键盘中文输入的容错体验；主流输入法纠错默认开启。
   - 判断依据：`TypoPinyinAlgebra.kt` 已有临近键/错序/漏字母/重复字母四类规则，但默认关闭，需独立建立误改率与性能评测。README 的 1.06% 是短词模型重排结果，不能作为拼音纠错规则的测量值。
   - 实现难度：**中**。规则已在，难点在于纠错候选与正常候选的混排权重、干扰抑制（README 自述"可能增加 CPU 开销和干扰候选"）和验收数据集扩充。
   - 相关位置：`engine-rime/.../TypoPinyinAlgebra.kt`、`PinyinAlgebra.kt:10`、`docs/input-strategy-comparison.md`。

3. **词库无新词/热词更新通道**
   - 影响范围：长期使用后候选陈旧——新词、网络用语、专有名词永远缺失；这是静态词库输入法的慢性死亡点。
   - 判断依据：词库为构建期钉死的 luna_pinyin；librime 用户词典关闭；唯一更新通道是手工制作的语言包，无市场、无示例、仅 zh/en。
   - 实现难度：**中**。可扩展 `language-pack` 为"词库增量包"（纯数据、沿用现有 zip 校验与 SHA-256 钉死机制），提供官方构建脚本与签名/哈希清单，用户从发布页手动下载导入——完全符合离线约束。
   - 相关位置：`language-pack/.../LanguagePackParser.kt:12-68`、`LanguagePackEngine.kt:139-141`、`engine-rime` 资源部署边界。

4. **ARM 真机性能验收缺失（验收缺口，非代码缺口）**
   - 影响范围：发布可信度；当前全部性能数据来自 x86_64 模拟器。
   - 判断依据：README"ARM 真机、厂商输入框以及真实系统身份认证流程仍需扩大验收"；`docs/project-progress.md` Next priorities 第一条。
   - 实现难度：**低**（工程已有 `InputLatencyTest`/`InputPipelineTest` 插桩），缺的是设备矩阵执行与记录。
   - 相关位置：`app/src/androidTest/.../InputLatencyTest.kt`、`tools/test-input-experience.ps1`。

### P1 — 显著影响日常使用，离线约束内可做

5. **英文引擎过于简陋**
   - 影响范围：英文输入体验全面落后：约 179 词（`DefaultEnglishLexicon.kt`）、纯前缀匹配、无纠错、无翻页（`EnglishInputEngine.kt:54`）。
   - 实现难度：**中**。引入离线英文词频表（如 wordfreq 截断版，注意许可证）、n-gram 或 BK-tree 纠错、复用 `CandidateWindow` 翻页。
   - 判断依据：Gboard/微软拼音英文纠错与联想是基础能力。

6. **无滑行输入（Glide Typing）**
   - 影响范围：单手/快速输入场景；Gboard 标志性能力。
   - 实现难度：**高**。需要手势轨迹采样→按键序列→拼音模糊匹配整条链路，且必须与现有矩形触控模型共存。
   - 相关位置：`ime-ui/.../KeyboardPanel.kt`、`KeyboardKeyView.kt`（当前纯点按模型）。

7. **快捷短语/常用语无键盘面板入口**
   - 影响范围：用户词组只能管理页维护，输入时无快速插入面板；主流输入法均有"常用语"工具栏入口。
   - 实现难度：**低中**。词库已有（`UserLexiconRepository`），缺的是 `ime-ui` 面板与 `ZeroInputView` 面板模式枚举扩展。
   - 相关位置：`ZeroInputView.kt:501-506`（面板模式）、`user-data/.../UserLexiconRepository.kt`。

8. **无障碍仅基础覆盖**
   - 影响范围：视障用户无法获知候选与上屏结果；无 `announceForAccessibility`、无候选播报、无按键音反馈。
   - 实现难度：**低中**。
   - 相关位置：`ime-ui/.../CandidateStripView.kt`、`KeyboardPanel.kt:168-188`（已有 stateDescription 基础）。

9. **剪贴板历史不可用（刻意取舍下的体验缺口）**
   - 影响范围：用户习惯的"复制多条→选择粘贴"不存在；安全剪贴板是认证保险库，不替代历史。
   - 边界：撤回“最近 N 条明文片段”建议。不得后台读取系统剪贴板正文、缓存明文或绕过逐次认证；默认关闭也不能豁免。后续只能在现有主动导入、私有加密与认证访问边界内设计体验。
   - 判断依据：`docs/threat-model.md`、ADR 0002/0007 的既定边界。

### P2 — 锦上添花

10. **emoji 库约 300 条**，Unicode 全集 3600+；有搜索/收藏/最近/自定义架构，扩量是纯数据工作（`EmojiCatalog.kt`、`AdditionalEmoji.kt`）。难度低。
11. **无单手模式/浮动键盘/按键弹泡/按键音**；主题仅 4 预设不可自定义。难度低-中。
12. **iQOO 厂商剪贴板历史清理未实现**（README 明示）；需要厂商接口验证，难度高且不确定。
13. **硬件键盘不接管按键**（`ZeroInputService.kt:454-467` 仅强制显示软键盘）；平板/折叠屏外接键盘场景缺失。难度中。
14. **无表情导入导出、无颜文字批量导入**（README 明示）。难度低。

---

## 第五部分：已实现但需改进项（按优先级）

### P0

1. **Rime secondary 已按需创建，收益待测**——首次请求“相近读音”才后台创建，过期/关闭路径释放 native 会话，见[实施记录](../performance-analysis.md)。两个会话共享运行时及词典，原“总内存翻倍”推断没有测量支持；仍需 ARM 设备峰值与稳态内存数据。

2. **模型重排误改率超自定门槛**——留出样例首选正确 36→47 条，但 1 条被改错（1.06% > 0.5% 上限，README 如实披露）。当前默认关闭是正确决策；要转为默认功能需先扩大评测集并压低误改（提高 margin、限制候选对、引入个人词频护栏）。难度中高。

### P1

3. **Keystore 认证绑定需独立设计**——当前正文访问使用应用层一次性授权，密钥未设 `setUserAuthenticationRequired`。不能给通用加密适配器统一加标志，否则会影响无需逐次认证的词库/索引/表情后台访问。需区分密钥用途、平台认证集成、失效恢复和已有数据迁移或删除，并先确认策略。本轮保持现状；读取丢失密钥时直接失败，不自动重生成。

4. **安全存储测试已补齐本轮边界**——security 新增 10 项纯 JVM 测试；原有 `ClipboardImportVaultTest`、`EncryptedUserStoreTest`、`UserLexiconBoundaryTest` 与表情加密测试已覆盖 Android 仓库边界，不能因不在模块 `src/test` 中就判断为无测试。新增 13 项设备回归覆盖失败删除、失效密钥、密文解析和清除竞态。真实生物识别、永久失效密钥、API 26 和断电恢复仍未验证，见[专项报告](security-storage-validation.md)。

5. **热路径需按当前实现复测**——设置镜像、英文循环/scratch、键盘及 emoji 主题缓存、Enter 局部重绑已实施。原性能报告中的行号、分配量与 3-12% 收益估计不能当作当前测量；剩余候选处理与分配应先插桩再定位。

6. **线程合并需测量调度影响**——原报告统计多个有界执行器，但不能仅按线程数直接合并；慢存储任务可能阻塞选区复制和粘贴。先测并发、尾延迟、拒绝和取消行为，再评估共享队列，本轮未调整线程模型。

7. **英文引擎实现质量**——见第四部分第 5 条，此处不再重复；`createSnapshot` 序列管道已优化（`EnglishInputEngine.kt:98-115`），但词表与算法层面是能力问题而非性能问题。

8. **`UserLexiconRepository` 整文件 JSON 加解密 + 大锁**——2 万条规模下全量排序（`suggestionPage`，`:51-58`）与整文件明文驻留有压力；建议写入时维护有序拷贝/前缀索引。难度中。

### P2

9. **个人候选学习异常静默**——`InputSessionController.kt:419` `runCatching` 吞掉所有学习写入异常，失败不可见（至少应有一次性降级标记或统计）。难度低。
10. **`EmojiCatalog.search` 每键编译正则**（`EmojiCatalog.kt:93`）、`ExpandedCandidatesView` 每次 new DiffUtil.Callback（`:101`）、`ExpressionBrowserState.visible` 全量拷贝（`:37-50`）等 P2 级分配冗余。难度低。
11. **加密存储未考虑 DirectBoot / deviceProtectedStorage 分区**——`EncryptedFileStore.kt` 现状可用，但设备未解锁场景（如开机即收输入请求）行为未定义。难度低，优先级低。
12. **系统剪贴板清理非原子**——`ClipboardGuardSession.kt:92` 自认竞态（检查后刚写入的新内容可能被清），已在 UI 文案披露；平台 API 限制，无完美解，保持披露即可。

---

## 第六部分：总体建议路线

1. **先补证据再谈优化**：P0 第 4 条（ARM 真机验收）成本最低、收益最直接，是一切性能结论的前提。
2. **输入能力继续推进**：已有基础联想，下一步评估语料覆盖；拼音纠错与短词模型分别建设效果评测；离线词库更新先设计纯数据构建和验证流程。
3. **英文引擎单独立项**：它是当前与主流差距最悬殊的子系统，且改动隔离在 `engine-english` 一个模块内，风险最小。
4. **存储可靠性继续补证据**：本轮负向测试与修复已完成；接下来补 API 26/ARM、物理认证和进程终止场景。Keystore 绑定认证须另行确认数据处置与交互方案。
5. **功能扩展必须保留既有边界**：不联网、不采集系统剪贴板正文、不建立明文历史缓存。默认关闭并不能授权弱化这些要求。

---

*当前验收以专项验证记录为准；历史规模和评分未重新全面审计，旧行号可能随实现变化。*
