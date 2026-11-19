# 隐写安全与体验修复核查

日期：2026-11-19。核查对象：《隐写输入模块安全审计与体验评估》（2026-11-19，S1–S8 / U1–U20 编号来源；该文件现已按核查结果更新为[遗留问题清单](2026-11-19-stego-security-ux-audit.md)）、[修复说明](2026-11-19-stego-security-ux-resolution.md) 与当前工作区源码。

> 二次回源已完成，最新结论见第 6 节。第 1–5 节保留首次独立核查记录；其中 S8“没有 begin 预算测试”和 R3“已确认竞态”的判断需更正，R1 需按候选与直接输入区分，R2 已修复。当前遗留清单已同步。

核查方式：只读回源代码逐项比对，独立重跑可在本机执行的构建与测试。未修改生产代码，未提交，未改动原有未提交内容。修复说明中的平台矩阵不在本轮重跑范围，仅核对其留存的证据文件。

## 1. 本轮独立执行的验证

| 命令 | 结果 |
| --- | --- |
| `./gradlew --offline :app:compileDebugKotlin` | 通过 |
| `./gradlew --offline :app:testDebugUnitTest --tests "dev.zeroinput.ime.textstego.*" --tests "dev.zeroinput.ime.input.*" :ime-core:test` | 通过；app 子集 57 项、ime-core 全部，0 失败 0 错误 |
| `./gradlew --offline -q privacyCheck :app:compileDebugAndroidTestKotlin` | 通过 |
| `./gradlew --offline :app:lintDebug` | 通过，报告为 `No issues found.` |

修复说明声称的 1153 项单测（2 项跳过）与 52 项平台用例，本轮未重跑平台部分；核对其留存证据 `stego-audit-generation-platform.log`（`OK (52 tests)`）、`stego-audit-unit-results.json`（total 1153 / skipped 2 / failures 0）时间戳为 20:06–20:07，早于本次核查，内容与说明一致。

## 2. 安全项

| 编号 | 核查结论 | 依据 |
| --- | --- | --- |
| S1 | **已修复** | `begin()` 现在写入独立 120 秒截止；`prepare()` 入口 `touch()`；`fail()` 回到 EDITING 并重新计时，`complete()` 仍为 30 秒。原缺陷路径（空闲近 2 分钟后加密、KDF 完成即被丢弃）不再成立 |
| S2 | **已修复** | `LocalDraftInput` 新增 `maxUtf8Bytes`，键入（`commitText`，含 `commitPairedText` 间接路径）、`replaceCommittedText`、`appendImported` 统一走 `withinByteBudget`；`enqueue`/`nextField`/`saveField`/`prepare` 的 `submittedChars()==null` 均改为显式 `stego_length_limit` 反馈，不再静默返回 |
| S3 | **按设计收敛，残余窗口仍在** | `onFinishInputView` 与 `endInputSession` 先关闭工作区；worker 侧补 `expectedVaultGeneration == vault.captureGeneration()`。前台资格仍在主线程先行判定、worker 复查，窗口变化与回调之间的非原子性属平台限制，说明已如实记录 |
| S4 | **已修复** | `offerPaste` 返回 `Boolean` 并校验全局开关、来源开关、删除代次与字段策略；调用方按返回值提示“已读取”或“无法粘贴”，不再对丢弃内容报成功 |
| S5 | **已修复** | `authenticate()` 起始 `resetDeadline(30_000, stego_authentication_expired)`；成功后再起 30 秒确认窗口；取消返回 120 秒编辑预算 |
| S6 | **已修复** | 认证回调入口即 `detour = false`，暂停豁免不再延续到回调之后 |
| S7 | **已缓解** | `emitCommittedText = false` 使隐写草稿不再为未使用参数复制全文；掩码只读 `length`；`exactUtf8ByteLength` 直接遍历计数，不产生字节或字符串副本。显式显示、引擎与平台回调仍会产生不可擦除 String，属已披露的平台限制 |
| S8 | **部分补齐** | 新增字节边界、门控预警与续期、凭据显示/清空、粘贴代次与全局开关等回归。**缺口**：没有直接断言 `begin()` 授予新的 120 秒 WORKING 预算的用例；系统剪贴板真实拒绝矩阵仍未覆盖 |

密码学与格式未变：`security/` 无改动，无 Manifest 权限或导出组件变更。ADR 0039 的 `random+file` 公式已改为 `HMAC(fileFactor, F(D("random-key-file"), random32))`，与 `MessageKeys.factor` 实现一致；bootstrap 版本说明补充为“由 v1 域字符串与认证元数据绑定”，均为规格勘误，不改线上字节。

## 3. 体验项

| 编号 | 核查结论 | 说明 |
| --- | --- | --- |
| U1 | **未修复，仅纠正提醒** | 密钥文件往返仍清空全部草稿。`stego_file_warning` 现明确“清除全部草稿、先选文件、返回后重新导入载文”；缺文件时 `stego_file_missing` 同样引导。属已确认的产品取舍，ADR 有记录 |
| U2 | **部分修复，且原报告有误** | 凭据字段本就走英文布局（`renderTextStegoInput(null)` → `renderDraftLanguage(ENGLISH)`），原报告“拼音被逐字符当密码”的表述不成立。真实缺口是无粘贴、无光标、强制掩码，现以提示说明并以“继续输入自动重新掩码”“确认后清空”缓解 |
| U3 | **未修复，仅提示** | 随机共享密钥仍只能手动转录；新提示建议跨设备改用口令或密钥文件 |
| U4 | 已修复 | 外部光标/选区变化关闭工作区时给出 `stego_selection_cleared` 提示 |
| U5 | 已修复 | 最后 30 秒每秒刷新剩余时间；显式编辑续期；私有宿主新增 `onDraftActivity` 续期 |
| U6 | 已缓解 | 重新编辑前确认，文案说明结果、秘密、凭据与文件因子将被清除；未从结果恢复明文 |
| U7 | 部分修复 | 私密空间选项追加索引已有的更新时间，仍不显示分类标签（认证前不读标签符合索引边界） |
| U8 | 已修复 | 无可用来源时不弹来源菜单、不显示粘贴按钮；安全剪贴板关闭时 `VAULT` 入口同步隐藏 |
| U9 | 已修复 | 选区导入移出“更多”，在解密编辑态且可插入时显示 |
| U10 | 已修复 | 输入、命令、编辑动作与算法调整均清除旧 notice |
| U11 | 已修复 | 专用长度文案说明 UTF-8 字节与各字段预算 |
| U12 | 已修复 | 新增“下一项”按钮，复用既有字段切换规则 |
| U13 | 部分修复 | 仅公开文本向无障碍暴露，其余字段保持排除；新增可见“粘贴此项”，长按不再是唯一入口 |
| U14 | 已修复 | 模式标签简化为“加密/解密”，并以选中态着色 |
| U15 | 已修复 | 宽度按钮显示 `%d 位`，英文为 `bits` |
| U16 | 已修复 | 新增“清空此项”；秘密与凭据需确认 |
| U17 | 已修复 | 状态区为 `ACCESSIBILITY_LIVE_REGION_POLITE`，不含正文 |
| U18 | 已修复 | 自定义位数改为中性范围说明，仅在无效提交时 `setError` |
| U19 | 未修复（按设计） | 未加 `configChanges`、未恢复秘密状态；旋转仍清除，已在 ADR 与说明中写明 |
| U20 | 部分修复 | 首屏说明列出离开、外部光标变化与超时清除；关闭后提示已清除。仍未加关闭确认（避免拖延撤销） |

## 4. 修复本身引入或遗留的问题

1. **达到上限后需要一次编辑才能提交**：预算拒绝会把 `lengthLimitReached` 置位，`submittedChars()` 随即返回 `null`，此时文本本身可能恰好合法（例如 2048 字节整）。用户需先退格或清空；且拒绝后的第一次退格只清除拒绝状态、不删除字符，观感上像“退格没反应”。该行为有平台用例覆盖（`LocalDraftInputTest`），属有意设计，但建议在长度文案或状态区分上说明“删除一个字符后可继续”。
2. **长度文案复用**：`submittedChars()` 在组合输入未能结束时同样返回 `null`，此时仍提示“已达长度上限”，原因不准确。建议区分“长度超限”和“组合未完成”。
3. **选择变化提示的竞态**：`submit()` 先调用编辑器回调再 `close()`，若 `onUpdateSelection` 经 IPC 在该窗口内到达，会先弹“外部光标或选区已变化”。概率低、仅文案误导，可考虑在插入前先标记忽略下一次选择回调。
4. **U1/U3 仍是实际可用性缺口**：提醒变准确了，但“选密钥文件即丢失载文”“随机密钥无法分发”没有改变。若后续要动，需要新的边界决定，不能由实现自行放宽。
5. **测试缺口**：S1 的核心两行改动（`begin()` 续期）缺少直接的单元断言；建议在 `ime-core` 补一条纯 JVM 门控用例，避免今后重构悄悄退回。

## 5. 结论

本轮修复与说明基本一致：S1–S7 的缺陷路径已在源码层消除或按已披露边界收敛，S2/S4 还补了负向用例；U4、U5、U8–U18 已落地，U1/U3 明确为产品取舍，U19 保持不变。未发现修复过程放宽隐私边界、改变密文格式或新增权限；`privacyCheck`、Lint 与相关单测在本机独立复跑通过。

原审计中对凭据字段“中文拼音被逐字符当密码”的判断有误，此处更正：该字段一直使用英文布局。其余结论维持。

## 6. 二次回源复核与修正

本次按用户已有授权核查并修复，不改变导航/选区/重建清除草稿、凭据禁止粘贴及单次插入边界。首次核查的“只读”说明仅适用于第 1–5 节记录。

| 项目 | 二次结论 | 处置 |
| --- | --- | --- |
| S8 门控测试缺失 | 误报：漏读 `StegoCarrierTest` 顶部的 `operation near editing expiry has a fresh bounded working window` | 既有测试已涵盖第 119 秒开始后越过原编辑截止仍可完成；另加强新预算恰为 120000 毫秒、119999 有效及 120000 失效断言 |
| R1 候选拒绝后阻止提交 | 设计保护，不能按合法前缀自动放行 | `PendingEngineCommit` 仍有用户未确认放弃的输入；首次退格明确撤销，随后可提交原有合法正文，无须再删除字符。既有回归已有此保护，本次补直接断言 |
| R1 直接输入拒绝后的恢复 | 真实体验不一致，已修复 | 新回归复现直接字符/成对符号/无组合换行拒绝后首次退格删除正文。现只撤销拒绝状态、保留已接受正文；第二次退格正常删除。超限提示说明未加入及撤销方法 |
| R2 组合未结束却提示超限 | 成立，已修复 | 只读 `submissionBlockReason` 区分预算拒绝和组合未完成；加解密、下一项、保存共用 `submittedField`。用持续改变但不结束的构造引擎验证有界完成后仍组合的路径 |
| R3 插入/关闭被 IPC 选区回调抢占 | 未证实，原文由源码顺序推导概率竞态的依据不足 | AOSP `IInputMethodSessionWrapper` 通过 `HandlerCaller` 向 IME 主线程分派跨进程通知，不能抢占当前主线程上的连续提交/关闭。未增加忽略下一次回调；真实选区变化继续撤销 |
| U1/U2/U3/U6/U19 | 仍是已确认取舍与能力限制 | 不新增文件往返草稿保留、凭据粘贴、密钥导出、预览秘密回填或旋转恢复 |
| U7 与 S8 平台矩阵 | 遗留 | 类别辨识与系统剪贴板完整拒绝矩阵未在本轮补齐 |

R3 平台依据：[IInputMethodSessionWrapper.java](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/inputmethodservice/IInputMethodSessionWrapper.java)、[HandlerCaller.java](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/com/android/internal/os/HandlerCaller.java)。同进程主线程直接调用可同步分派，特殊 OEM/同步重入未独立验证，不把该说明当作所有实现均无此问题的证明。先 `close()` 再提交会使当前目标的面板资格校验失败，也不能作为无代价修复。

本轮验证：app 572 项、ime-core 204 项，合计 **774 通过、2 项可选 provider 跳过、0 失败/错误**；包含 up-to-date 的 core 报告。统计：`.tmp-evidence/stego-verification-followup-unit-results.json`。新增用例覆盖精确字节满额后显式撤销可直接提交、直接字符/成对符号/换行拒绝、其他草稿旧行为、非终止组合的准确反馈和精确门控截止。

```powershell
./gradlew.bat --offline --no-parallel :app:testDebugUnitTest :ime-core:testDebugUnitTest `
  :app:assembleDebug :app:assembleDebugAndroidTest privacyCheck :app:lintDebug `
  "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
```

最终命令退出 0，`BUILD SUCCESSFUL in 3m 8s`；真实 Rime x86_64 Debug/AndroidTest、privacyCheck 均通过，Debug Lint 为 `No issues found.`。日志：`.tmp-evidence/stego-verification-followup-scoped-final.log`。中英资源名称一致，XML 可解析，`git diff --check` 通过。

API 37 隔离模拟器最终版 **24 项通过、0 跳过/失败**：`LocalDraftInputTest` 12 项、`StegoAuditRegressionTest` 5 项、`StegoPanelInteractionTest` 4 项及真实编辑器自动插入、选区变化撤销、预览单次插入 3 项。含真实 Rime 候选拒绝恢复。日志：`.tmp-evidence/stego-verification-followup-platform-final.log`。320×480 dp、深色、fontScale 1.3 下重复 9 项 UI/草稿回归全部通过，日志：`.tmp-evidence/stego-verification-followup-small-dark.log`。9 项是重复验收，不计为新增不同用例。

首次新增测试发现直接输入拒绝后退格会删正文，并修正非终止引擎构造样本；首次平台运行发现共享草稿行为变化（24 项中 1 项失败）。随后将恢复策略限定为隐写显式启用，保留其他草稿旧语义，并添加默认行为单测。最终 24 项完整复跑通过。失败日志保留，不计为成功证据。

模拟器显示/密度/fontScale/深浅主题已恢复为启动前值，默认 IME 未变，未设置临时凭据；任务启动的模拟器已关闭。最终 Debug x86_64 主 APK 为 98,409,615 字节，SHA-256 `5f09cd4561be052ce5a997f441af3c2ebfdab8117a014235c8fc8f5876386e65`，记录在 `.tmp-evidence/stego-verification-followup-artifact.json`。它替代上一轮模拟器产物，不是新 ARM64 分发或正式 Release。

未重跑横屏、完整系统剪贴板拒绝矩阵、旧 Android、实体 ARM/OEM、消息通道、fuzz 或独立密码学审计；上一轮证据仍仅作历史证据。无新增权限/导出/数据格式/密码学/凭据粘贴路径，无提交或发布。


## 7. 代码复查、提交与重新打包

按用户“复查代码，无问题后提交推送并重新打包”的授权完成本轮。复查覆盖本次隐写修复及其依赖：门控期限与认证/导航撤销、草稿字节预算和组合拒绝、退格恢复的显式启用范围、粘贴一次性结果与删除代次、反馈与资源一致性。未发现新增阻断问题；第 6 节列出的产品边界与未验证项仍有效。

隐写及必要前置依赖已提交为 `33a2db9`。为验证提交本身，在独立干净检出构建，未带入工作区的表情导出修复和 AI 评估。干净 Windows 检出暴露两个固定输入的 CRLF 问题，分别通过 `88aeed2`、`af8a84b` 的 `.gitattributes` 精确保留许可证及固定 Rime `default.yaml` 字节；不修改上游内容、固定哈希或校验门禁。初次提交夹带的两条表情导出资源通过 `4481541` 移除，原工作区保留它们供所属工作使用。以上提交已推送 `origin/main`。

最终 APK 的源码提交为 **`44815410e6a7ba365ac720303f25862088d934c6`**，独立检出为 `C:/Users/yixun/.codex/worktrees/stego-reviewed-package/zeroInput`，构建完成时 `git status --porcelain` 为空。代码文件已与 Git blob 核对（仅容许检出行尾差异），许可证与固定配置的 `core.autocrlf=true` 检出样本与 Git blob 字节完全一致。临时 Gradle home 复用本机缓存和既有镜像配置，仅本轮进程生效。

```powershell
./tools/package-test-apk.ps1 -Abi arm64-v8a -MaxApkBytes 180000000 `
  -OutputDirectory 'K:/zeroInput/app/build/outputs/test-apk'
```

最终运行未使用 `SkipChecks`，脚本退出 0。全量 `test`、`privacyCheck`、`:app:lintDebug` 检查阶段 `BUILD SUCCESSFUL in 1m 22s`，真实 Rime ARM64 打包阶段 `BUILD SUCCESSFUL in 31s`。完整 Debug/Release 单测 XML 报告共 2288 项：**2284 通过、4 项可选 provider 跳过、0 失败/错误**，包括 up-to-date/from-cache 的套件，不声称每项本次重新执行。Lint 为 `No issues found.`；APK 内容、签名、ABI、包名及权限检查通过。此前失败运行只作诊断证据，不计为通过。

产物： [ARM64 Debug 测试 APK](../../app/build/outputs/test-apk/20261119-213202/zero-fish-input-0.7.7-debug-arm64-v8a.apk)，包名 `dev.zeroinput.ime.debug`，96,418,147 字节（96.418 MB），SHA-256：

```text
06e7de44b13ea15085e3e401d75c7d98c610c2115cebb14dd27f644695e9a2e3
```

同目录 `SHA256SUMS.txt` 中 APK、许可证 notices ZIP、手写对应源码 ZIP 三项重新计算全部匹配。`BUILD-PROVENANCE.json` 记录包名、版本、来源 APK 路径、时间、大小和哈希；源码提交另记录于本节与 `.tmp-evidence/stego-reviewed-unit-results.json`，该脚本生成的 provenance 本身不含 Git 提交。180 MB 上限通过，独立的 80 MB 目标仍未达到。

最终日志：`.tmp-evidence/stego-reviewed-arm64-package-complete.log`；报告统计、源码提交及复算校验值：`.tmp-evidence/stego-reviewed-unit-results.json`；检出字节检查：`.tmp-evidence/stego-reviewed-byte-check.json`。本轮未重新运行平台用例，未安装到实体 ARM/OEM、未补旧 Android 或完整系统剪贴板拒绝矩阵；第 6 节平台结果仅为上一轮证据。本产物为 Debug 签名测试包，无正式 Release 构建或发布。
