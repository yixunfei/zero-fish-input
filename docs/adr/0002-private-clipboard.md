# ADR 0002: Private authenticated clipboard

Status: accepted

“安全剪贴板”实现为 ZeroInput 私有加密内容库，不复用 Android 系统剪贴板。它默认关闭，不后台采集内容，不向其他应用导出 Provider 或 Service。用户可在管理页主动添加内容，或通过选中文字与文本分享入口在认证并确认后导入，并从输入法面板主动选择粘贴。导入边界见 [ADR 0006](0006-private-text-import.md)。

每次读取均通过 Android 生物识别或设备凭据认证。认证成功后仅把选中的单条内容保留在内存中直至提交，不建立明文缓存。

输入法面板采用 [ADR 0012](0012-keyboard-private-copy-and-foreground-cleanup.md) 的两阶段粘贴。
系统认证期间仅保留条目 ID、原编辑器公开标识、删除代次及未消费授权；系统凭据页引发的
会话解绑不会把该意图误当作可提交结果。回到原应用后，用户必须在 30 秒内点击“确认粘贴”，
此时才绑定当前会话令牌、实际 `InputConnection` 和交互序号，后台读取并校验后提交。
返回绑定后切换编辑器、结束会话、继续输入或修改设置等使请求失效。认证回调不读取正文，
也不自动提交。安全剪贴板索引和正文的 Keystore
解密、JSON 解析均在输入法主线程之外执行，确认粘贴才排队正文读取，避免首次解密或较大
vault 卡住按键响应。


## Explicit whole-vault deletion (2026-10-08)

The user approved an independent Settings > Clear secure clipboard command.
After explicit destructive confirmation it purges both encrypted files and both
isolated keys without decrypting content or requiring a read grant. It advances
the deletion generation before waiting for the vault lock, so queued additions,
reads and authenticated operations cannot restore or deliver pre-deletion data.
Both deletions are attempted even when one fails; a failure is reported and the
vault remains unavailable in-process until an explicit retry succeeds.

Turning the feature off retains encrypted entries. Clear personalization data
continues to cover only the items stated in its confirmation. Neither action
implicitly purges the vault. This avoids treating an ordinary feature toggle as
an irreversible deletion action. Whole-vault deletion works while the feature is
disabled or its contents cannot be decrypted; it never authorizes a plaintext read.
Storage formats and key aliases are unchanged. Settings owns confirmation and
background execution; user-data owns serialized deletion and generations.
