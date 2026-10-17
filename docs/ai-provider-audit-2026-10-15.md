# AI Provider 审核与修复记录（2026-10-15）

状态：实现、自动化回归和真实服务请求验收已完成。

## 修复范围

本轮只修改现有 AI Provider、模型发现和设置表单路径，保留配置格式、已有配置、HTTPS 限制、无重定向和无自动换协议/换地址重试的边界。

- Provider 地址解析会在发起请求前拒绝零宽、双向等 Unicode 格式字符。普通首尾空白仍按现有规则处理；不会静默删除隐藏字符，也不会猜测另一个地址。
- 新增 Provider 时地址保持为空，不再把示例或 OpenAI 地址当成请求目标。新增和编辑分别显示 API key 说明；编辑时留空才保留已有密钥。
- Provider 表单改用主题化 Material outlined 控件。文本输入不再显示旧的 EditText 下划线，协议和模型选择使用正确的下拉箭头样式。长模型 ID 在默认模型控件内最多显示两行并在末尾省略，选择列表有可读的行高和多行布局。
- 没有模型时隐藏默认模型、图片/音频能力和空下拉控件，只显示可执行的获取或手动填写提示。模型被删除后这些控件会再次隐藏。
- 模型发现、协议修改、密钥/地址修改和窗口关闭都会取消旧请求；过期回调不能修改新草稿。获取失败会保留 HTTP 状态分类，编辑地址或密钥后可以重新获取，但不会自动重试或保存草稿。
- 实际设置流程的回归测试要求目录请求成功且包含指定模型，不能只靠后续生成请求通过来掩盖目录失败。

## 真实服务验收

临时凭据只通过运行时环境变量传给测试进程，没有写入源码、资源、测试夹具、日志或本报告。请求目标为用户提供的 `https://yibei-apc.com/v1`，使用真实的生产 `OpenAiCompatibleProvider`。

目录请求返回 HTTP 200，并解析出 22 个模型：

```text
gemini-2.5-pro
gemini-3-flash
gemini-3-flash-agent
gemini-3.1-flash-image
gemini-3.1-flash-lite
gemini-3.1-pro-high
gemini-3.1-pro-low
gemini-3.6-flash
gemini-3.6-flash-high
gemini-3.6-flash-low
gemini-3.6-flash-medium
gemini-3.6-flash-tiered
gemini-3.7-flash
gemini-3.7-flash-high
gemini-3.7-flash-low
gemini-3.7-flash-medium
gemini-3.7-flash-tiered
gemini-3.8-flash
gemini-3.8-flash-high
gemini-3.8-flash-low
gemini-3.8-flash-medium
gemini-3.8-flash-tiered
```

使用 `gemini-3-flash` 完成了两种真实生成请求：普通 JSON 响应和 SSE 流式响应均返回非空文本并正常结束；使用无效凭据的请求被识别为鉴权失败。真实结果保存在以下只含测试元数据的 JUnit XML 中：

- `build/ai-provider-20261015/live-host-result.xml`：目录、JSON 和 SSE 的生产适配器测试，`tests=1 skipped=0 failures=0`。
- `build/ai-provider-20261015/live-editor-result.xml`：实际 Provider 编辑器获取目录、选择指定模型并在显式保存后写入内存配置，`tests=1 skipped=0 failures=0`。

编辑器测试没有在获取或选择阶段保存草稿，只有用户点击保存后才写入配置，并校验写入的地址、模型和运行时输入的密钥一致。XML 不包含密钥或响应正文。

## 自动化与产物验证

相关模块和 Android 资源使用以下命令验证：

```powershell
./gradlew.bat :ai-api:test :app:testDebugUnitTest :user-data:testDebugUnitTest `
  privacyCheck :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest `
  --no-parallel "-Pandroid.injected.build.abi=x86_64" -PrequireRime=true
```

该命令覆盖 AI API、设置表单、用户数据、隐私清单、Debug Lint、Debug APK 和 instrumentation APK；普通测试运行中仅跳过需要显式临时凭据的两个远程测试，远程测试已单独以 `skipped=0` 成功运行。Robolectric 原生渲染检查了中文窄屏浅色竖屏和英文短屏深色横屏，确认 outlined 输入框、下拉箭头、两行长模型文本、滚动区域以及固定的保存/取消按钮均在窗口内。

官方测试包脚本也已完成 ARM64 Debug 打包和检查：

- APK：`app/build/outputs/test-apk/20261015-210617/zero-fish-input-0.5.0-debug-arm64-v8a.apk`
- SHA-256：`bfbd88986be1ba74f1fb48fb8480213d5bd8502b9b90a6c9184f0a24611b737e`
- 脚本检查：签名、ABI、权限、隐私检查和 Lint 均通过。

## 限制

原始报告的固定 HTTP 404 在本轮没有用同一组输入复现，因此不能把它归因于某一个服务端原因。现在能够确定的是：隐藏路径字符会被拒绝，错误状态会被明确呈现，真实目录和生成请求已经通过，且没有用备用地址或自动重试掩盖错误。

本机没有可用的 Android 设备或在线模拟器；硬件模拟器启动失败（WHPX `80070005`），软件模式保持离线后已停止。因此没有声称 Android 设备上的真实 TLS 请求或实机触控验收通过；真实联网证据来自主机执行的生产网络适配器和实际服务响应。交付 APK 是 Debug 测试包，不是签名 Release 包。
