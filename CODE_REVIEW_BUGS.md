# ZeroInput 代码复查报告

> 本文档只报告**当前存在的问题**。每轮全面复查后重写；已修复/不成立项不再保留。
> 最近更新：2026-10-10（第五轮修复复核）。基于基线提交 `84d500e` 及其后的修复。

## 结论概览

| 严重度 | 数量 | 编号 |
| --- | ---: | --- |
| 高危 | 0 | — |
| 中危 | 0 | — |
| 低危 | 0 | — |
| 性能 / 观察 | 8 | P1–P8 |

---

## 本轮复核结果

报告中的 M1、M2、L1、L2、L5、L6、L7、L9、L10 已修复并有对应单元测试或静态边界验证。L3 在复核时已由 `EmojiCatalogSnapshot.byValue` 提供 Map 查找；L4 的异步结果现在额外绑定发起请求的 `InputSessionController`，比单独的包名检查更强；L8 复核确认加载路径已经显式使用 `weights_only=True`，且仓库环境的 PyTorch 2.12.0 可加载固定 Mini 权重，因此这三项不再作为当前问题保留。

M1 的不可交付结果会立即关闭 Prepared 引擎，并在可用的 Handler 上移除延迟 Runnable。M2 会先解析资产生成目录，只有身份实际变化时才重建 native runtime；关闭路径同时重置已准备 revision。

---

---

## 性能与结构观察（不构成缺陷）

- **P1 · 语言包前缀索引内存放大**：`LanguagePackEngine.buildPrefixIndex`（`:132-144`）为每个 shortcut 每个前缀建 bucket，受 MAX_ENTRIES=50_000/MAX_TOTAL_TEXT_BYTES=32MB/shortcut≤64 约束有界。补充：`bucket.size >= MAX_CANDIDATES(8)` 提前 break 按字典文件行序截断（`:138-139`），前 8 个词条永久占满该前缀，后续更高频词条被丢弃——候选质量非最优但功能正确。扩大词典上限前需重新评估（限制索引前缀长度 + 排序依据）。
- **P2 · AI 上下文视图全量重建**：`AiContextView.render`（`:29`）每次 `removeAllViews()` 重建（≤16 引用+≤20 消息），有界。上限扩大前应改差量更新。
- **P3 · 会话转写全量拼接**：`AiWorkbenchPanelView.renderConversation`（`:264`）每次 `joinToString` 重建整个转写（≤20 消息×32k 字符），有界低频。
- **P4 · KeyboardLayoutHost.onMeasure 每次 currentWindowMetrics**：`KeyboardLayoutHost.kt:174-176`：binder 开销，面板展开/收起/候选变化时触发。建议缓存 windowHeight 仅在 onSizeChanged/配置变化时刷新。
- **P5 · SecureClipboardVault 全局 synchronized**：Keystore/磁盘抖动时放大锁占用。B5 修复后常规持锁写入已消除（仅索引不一致时重写），锁占用显著降低，维持结构观察。
- **P6 · commitEmoji 主线程串行 BoundedExecutors.purge** — `ZeroInputService.kt:1150`：队列容量仅 2，代价可忽略；仅记录该点位于按键路径。不建议改。
- **P7 · onStateChanged 每次 publish 都调 renderEngineStatus** — `ZeroInputService.kt:441`：内部 5 个 render* 调用+全量状态分支（`:1694-1711`）在每个按键 publish 都执行；若视图侧无短路，这是每键一次的冗余渲染链，建议视图侧加等价短路。
- **P8 · SecureClipboardManagerActivity 删除确认对话框将条目 label 明文放入 message** — `SecureClipboardManagerActivity.kt:235`：对话框受 FLAG_SECURE 保护且 label 非正文，需用户确认，风险可控。若威胁模型将 label 视为敏感元数据，应改为通用文案。

---

## 建议处理优先级

1. **M1**（一行修复：`!canDeliver` 时关闭 Prepared）→ 2. **M2**（资产 identity 未变时跳过 native 重初始化）→ 3. **L5**（vault.add 补 CharArray 重载）→ 4. **L6**（8.x 时间戳惰性捕获）→ 5. **L8**（固定 torch 版本）→ L1/L2/L3/L4/L7/L9/L10 与 P1–P8 视后续演进再议。

---

## 验证

- 相关模块单元测试、`privacyCheck`、`:app:lintDebug`：**BUILD SUCCESSFUL**。命令：

```powershell
./gradlew.bat :app:testDebugUnitTest :ime-core:testDebugUnitTest `
  :user-data:testDebugUnitTest :engine-rime:testDebugUnitTest privacyCheck `
  :app:lintDebug --no-parallel
```

- **未运行**：androidTest/模拟器/真机、`-PrequireRime=true` Rime 源码验证、Release APK 构建。
- 核心安全防线（密码学、两阶段粘贴、系统剪贴板隔离、AI 网络边界、无障碍最小化、语言包导入、XZ 词典校验、热路径线程纪律、构建供应链、资源双语一致性、数据清除、日志）经逐条读码复核全部在位，详见第五轮完整审查（本文档重写前的历史版本）。
