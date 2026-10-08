# Security policy

Offline input additions retain the same boundary: glide uses only bounded current
touches and public word lists, handwriting uses verified public stroke/OCR models,
and complete Emoji data/artwork is bundled. No input trace or handwriting is
stored, learned or transmitted. Worker results and code replay are revoked on
session, interaction, privacy and data-clear transitions; background revocation
does not manipulate UI. Floating/one-hand keyboard uses only the IME window,
with touches limited to its visible surface, and adds no overlay permission.
Public emoji variants do not grant access to personal favorites/history. Explicit
Backspace reads at most 64 cursor-local UTF-16 units to delete complete sequences,
without retaining context. See ADR 0016/0017 and docs/threat-model.md.

请勿在公开 Issue 中提交可能泄露用户输入、密钥或本地文件的安全报告。本仓库已启用
[GitHub 私密漏洞报告](https://github.com/yixunfei/zero-fish-input/security/advisories/new)。
请提供使用构造数据的复现步骤、影响版本和预期边界，勿附带真实输入、词库、片段或密钥。

当前 `v0.4.0` 为 Debug 签名测试预发布，优先修复主分支与最新预发布中的安全问题，尚无长期支持版本。
下载 APK 为 Debug 签名且可调试，仅适合测试；维护者正式签名与真机认证验收尚未完成。

## 支持边界

- 词联想默认开启，仅使用本次普通文本会话中本输入法成功上屏的最多 32 个 UTF-16 字符，
  查询内置公开词对，最多显示 8 项；不读取输入框已有正文、剪贴板或个人历史，不保存上下文。
  密码、未知类型、数字、邮箱、网址、禁止个性化、隐身和关闭学习时禁用。移动光标、切换会话、
  面板、设置或引擎、删除和直接插入文字会清空上下文；旧候选不能提交，空格和回车不会接受联想。
  被编辑器接受的显式联想点选通过现有加密词库学习词频，仍受会话与学习开关约束；同词的多个
  输入码合计频次采用饱和计数，避免溢出影响排序。可变缓冲区清零，JVM 查询临时字符串由垃圾
  回收处理，不承诺完整内存擦除。

- 上屏后的“重选上一词”仅保留当前会话最近一词，最多 128 个 UTF-16 单元；继续编辑、移动光标、
  切换会话或设置、销毁及 30 秒超时都会使其失效。重选前核对原输入连接、已确认的光标范围和
  前文；不匹配时不删除文字。不支持重新打开组合区间的应用可能无法使用此操作。
- 候选扩展和实验纠错完全离线，不记录触点轨迹；纠错默认关闭。新词学习仍使用加密词库及原有
  隐私和代次校验。独立的短词智能排序实验使用随包模型，没有新增应用网络权限或运行时下载。
- 离线手写只处理当前面板的有界笔迹和公开模型，不读取编辑器正文、剪贴板或个人记录，也不学习；
  敏感输入框使用相同公开识别路径。切换面板、会话或设置会使旧结果失效，候选需主动点选且再次
  校验当前输入连接。笔迹不落盘，应用缓冲区及时清零；native 内部副本无法保证逐字节清除。

- zero fish input 的设计目标是防止普通应用通过系统剪贴板或导出的 Android 组件读取私有内容。
- 本地数据使用 Android Keystore 管理的 AES-GCM 密钥加密。
- 密文读取遇到密钥丢失时直接失败，不自动生成替代密钥。密文篡改、截断、格式错误及文件
  提交/删除失败不能被报告为成功；保险库解析错误不附带正文或底层解析异常。
  一次性授权的有效期最多 30 秒，调用方不能延长，并发消费只能成功一次。
- 安全剪贴板清除会先使旧操作失效，并尝试删除正文、索引及各自密钥；失败后当前实例阻止
  继续读写，直到重新清除成功。索引更新失败时隐藏旧摘要，认证后再修复。
  正文与索引仍为两个独立原子文件，失败状态仅保存在进程内；进程重启后可能需要认证修复
  无正文索引或重新清除，不保证断电事务性。认证仍由现有应用层流程控制，本轮没有更改
  Keystore 的认证绑定策略或持久化格式。验证范围见[安全存储验证](docs/security-storage-validation.md)。
- 表情收藏与自定义颜文字使用独立加密文件及密钥，不参与词组导出。输入法在敏感编辑器、
  隐身或关闭学习时隐藏个人表情且不记录使用；自定义管理页禁止截屏与最近任务预览，离开时
  清空显示和草稿。清除个人数据会同时删除收藏、自定义内容、最近记录及各自密钥。
  失效会话、过期条目、未知数据版本及写入/删除失败均不能绕过校验或恢复已删除的数据。
- 安全剪贴板访问需要系统生物识别或设备凭据认证。
- 认证成功已排队但尚未交付时，取消和超时仍使其失效，重复或迟到回调不能恢复授权。
  安全剪贴板管理弹窗单独设置截屏保护，禁用状态保存、自动填充和内容捕获；
  关闭弹窗时清空输入草稿，离开页面时关闭私密弹窗。
- 粘贴必须在系统认证后返回输入框，再点击“确认粘贴”。认证导航期间应用仅短时保留未消费
  授权和条目标识；确认时才绑定当前输入连接并读取正文。授权 30 秒过期，不跨进程恢复；
  返回绑定后的编辑、会话切换、取消、设置变化及数据删除都会使操作失效。
- 键盘复制仅在用户点击后读取当前非敏感输入框选区，长度上限 8192 个 UTF-16 代码单元；
  读取在有界后台线程进行，会话、选区或设置变化使结果失效。非导出页面接收一次性内存草稿，
  延续原删除代次，认证并确认后才能保存，不读写系统剪贴板；超时、取消和销毁清除临时缓冲区。
- “复制到 ZeroInput”与文本分享仅提供待确认内容的接收入口。外部调用不代表用户授权，
  必须在 ZeroInput 内认证后主动确认保存；入口不能查询、返回或导出已有条目、标签与正文。
  超限、附件和无效请求拒绝处理，草稿不写入页面恢复状态，旧认证和已清除代次不能触发新写入。
- 该方案保护私有加密内容库，不接管系统复制或系统剪贴板权限；源应用已知的文字与用户主动
  插入目标应用后的文字不受私有库隔离保护。选择菜单支持情况取决于源应用。
- “系统剪贴板防护”是独立且默认关闭的误复制缓解功能。监听、键盘提醒、系统通知、清理方式
  和身份认证分别设置；不读取正文，不收录到私有库。通知权限只用于用户主动开启的通用提醒。
  认证清理需先完成系统身份认证，再在前台确认；旧请求在设置变化、服务退出和默认输入法变化后失效。
- 该功能只是尽量缩短误复制内容在系统剪贴板中的停留时间，并帮助用户清理；无法阻止写入瞬间的
  访问，默认不开启。无法阻止或识别第三方已经发生的读取，也无法清除其他应用的副本或历史。
  平台回调可能缺失，时间戳不保证唯一，检查与清空之间仍可能出现新内容并被误清空。
  这些限制在设置页和清理确认中说明，不能将本功能视为系统剪贴板访问控制。
- 独立悬浮提醒默认关闭，申请 `SYSTEM_ALERT_WINDOW` 前先解释原因和限制，用户确认后才打开
  系统授权页。悬浮窗仅显示通用状态，不读取页面、不显示正文、不直接清理；锁屏、撤权或进入
  本应用页面后移除。该权限不保证后台监听可用，也不提供读取任意选中文字或删除其他应用历史的能力。
- 主动检查当前项无需等待复制回调，检查后仍进入确认页并遵守独立的身份认证设置。未收到可访问
  元信息时不声称剪贴板一定为空；系统明确拒绝访问与操作失败分别反馈。
- 前台检查和清理使用可撤销的窗口焦点授权，不要求 ZeroInput 为当前输入法；该授权不提供后台
  监听能力。自动模式经用户明确开启后涵盖已有当前项，恢复防护或返回设置页时也尝试处理；
  监听和提醒本身不授权删除。后台清理仍校验存活服务与当前输入法身份。
- 已 root、系统镜像或无障碍服务被恶意控制的设备不在可防御边界内。
- 输入内容不会写入日志；问题报告不得自动附带输入内容。
- Debug 测试包的输入诊断栏仅显示当前编辑器包名、公开 `EditorInfo` 类型/选项、subtype、
  隐私策略、语言和引擎状态，用于排查第三方输入框兼容性；不显示输入文本、拼音、候选词、
  周边文本或异常堆栈，不写入日志或文件，会话结束即清除，Release 包不渲染该诊断。
- 默认关闭的短词智能排序仅使用当前输入会话中本输入法成功上屏的最多 16 个中文字符，
  不为模型读取编辑器正文、剪贴板或个人历史。密码、未知类型、邮箱、URI、隐身和禁用学习
  等输入不提供模型上下文。切换会话、结束视图、设置变化、移动光标或重选时清空并使旧结果失效。
  上下文及 token 不落盘、不记录、不联网；只缓存校验过的公开模型文件。应用缓冲区及时清零，
  native 内部临时副本的逐字节清除不能由应用保证；张量和会话在生命周期边界释放。
- AI 工作台默认关闭，且必须同时开启 AI 总开关与联网开关。它只处理用户在键盘 AI 面板主动
  提交的文本和用户明确选择的会话历史，不读取编辑器周边内容、选区、系统剪贴板、安全剪贴板、
  个人词库、emoji 历史或其他输入历史。密码、PIN、邮箱、网址、隐身、未知和隐私收紧的输入
  fail-closed。受限场景中的 AI 图标仅显示固定的不可用说明，不打开工作台、不加载会话、不发送请求；
  点击后重新校验隐私策略，已显示的旧图标不能绕过限制。网络仅由唯一的 OpenAI-compatible provider 发起，强制 HTTPS、无重定向、无查询
  参数或片段，并限制请求、超时、SSE 单行和整体响应大小。流式输出留在面板，只有用户点击
  “插入结果”才提交到当前编辑器；切换会话、设置、服务销毁、清除 AI 数据或编辑器都会使旧
  请求失效。AI 配置（含 API key）与可选会话历史使用相互独立的 AES-GCM/Keystore 存储，会话保存默认关闭，
  API key 不进入日志、Intent、异常或诊断。
  仅禁用预测建议的普通文本框可使用独立 AI 草稿，个人数据仍禁用；组合的敏感、标识符、无个性化及用户隐私限制仍拒绝 AI。
  设置中的主动模型检测复用唯一传输，仅发送固定短测试文本，不包含草稿、会话或附件，也不保存或插入结果。
  检测限制输出与超时，关闭弹窗、进入后台、修改配置及清除数据会取消；错误仅显示固定分类，不读取服务端错误正文。
  配置弹窗在临时切换应用时保留当前页面内的有界草稿，仅点击保存后才更新加密配置；
  取消、退出页面或页面销毁时清空输入框，不写入页面恢复状态，继续禁用截屏、自动填充和内容捕获。
- 未知编辑器类型与变体按敏感输入处理，不创建候选引擎、不读取或学习个人数据。
- 普通输入框的 `NO_SUGGESTIONS` 标志保留中文转换所需的公开候选，但禁用个人词组读取、
  学习、历史、模型上下文、英文预测和词联想；该策略同时传递给即时引擎和后台准备的引擎。
  密码与未知输入类型仍优先禁止引擎。准备进度与首次输入提醒只显示固定文案，不缓存或重放按键。Rime 就绪检测仅在
  后台使用固定公开测试文字，不连接编辑器、不学习、不记录正文，检测后清空组合状态。
- 用户词组导入使用固定结构的流式解析，限制为 5 MiB、20,000 条；非法字段、嵌套、重复项和
  超限合并整次拒绝，错误不包含文件内容。加密写入成功后才更新内存，清除操作使旧更新失效。
  自动学习也拒绝不完整的 Unicode 代理字符，避免保存时替换字符造成数据变化。
- 替换语言包时，暂存旧目录失败不会删除
- 语言包 manifest 与 JSON 词典在递归解析前限制为 16 层容器，拒绝非标准裸键、单引号、
  注释、NUL 与尾随文档，使用有界严格 UTF-8 解码。无效 JSON 不会回退为行词典或部分激活。
- AI 流中的非字符串正文以及明确的截断、过滤和工具调用终止不能成为可插入或可保存的完整结果。
- 替换语言包时，暂存旧目录失败不会删除
  该目录；新包激活失败则恢复已暂存的旧包。备份清理失败不回滚已成功激活的新包。
- 用户词组和个人颜文字导出前明确提示设备绑定范围及覆盖风险；导出文件使用各自独立的
  Android Keystore 别名加密。清除应用数据或删除对应密钥后，既有导出文件无法恢复；用户
  选择的文件位置及其外部同步行为由文件提供方控制，不属于应用内存储的保护范围。
- 中文配置变化会使旧引擎预热结果及待处理认证操作失效；隐私收紧仍立即取消组合与个性化读取。
- 简繁转换完全离线，词典固定版本并在构建时校验。生成的拼音索引不含用户数据，不启用 Rime 用户词典。
- 长按删除与候选点击均受会话和手势生命周期约束，失效操作不会延迟作用于新的输入框。
- 面板返回和表情分类手势沿用交互失效边界；横滑先取消条目点击，取消手势不得插入内容。外部光标移动只结束已有组合并清除旧候选，不读取周边正文，不学习该次组合，也不重写新选区。
- 自选键盘背景只接收用户通过系统选择器授权的图片，限制实际字节、源像素与解码尺寸；后台重编码去除
  元数据，以独立 AES-GCM/Keystore 密钥加密保存在 noBackupFilesDir。删除或恢复默认会使旧导入失效并
  删除副本和专用密钥；失败可重试。无新权限、联网、URI 长期授权或全局明文图片缓存。
  背景不透明度只混合键盘本身的背景，不使文字或窗口变透明；磨砂样式不截图、不读取其他应用。
- 键盘外观预览不连接输入框或个人数据，设置窗口禁止系统截图和预览；更换主题会清空旧视图绑定并取消旧交互。数字布局仅使用
  输入框公开类型信息，不改变 PIN、密码和未知编辑器的隐私策略。
- 内置中文引擎统一使用 Rime，移除测试词典引擎；九键布局切换仍校验
  预热请求身份；九键读音替换限制字符和长度，选择历史仅存在当前会话中，清空或关闭时释放。


AI 工作台的联网例外及剩余风险见 [ADR 0015](docs/adr/0015-ai-workbench-boundary.md)。
AI 内容确认页当前为非导出内部入口；文件、图片、音频仅由系统选择器主动挑选。
确认页不联网、不返回文本；内容两分钟内经普通输入框 AI 面板再次领取后，才可显式提交。
最多两个附件、总计 1 MB，文本限严格 UTF-8 和 16,384 字符；只支持 TXT、JPEG/PNG/WebP、WAV/MP3。
无持久 URI 授权、附件落盘、自动页面读取、相册扫描或录音。会话、设置变化及超时撤销临时内容。
Provider 可配置多组，图片与音频能力按模型声明；敏感编辑器仍拒绝 AI，附件不写入会话历史。
只有工作台主动提交的文字、所选上下文、附件与会话会送至配置的服务商；服务商的数据保存政策不受本项目控制。
关闭联网不影响离线输入与安全剪贴板。AI 草稿采用独立转换会话，不读取或学习个人数据；取消、
截断和过期结果不可插入。API key 弹窗与 AI 面板使用屏幕保护，所有会话正文只作纯文本处理。

Expression-search input is a bounded, memory-only local conversion draft with no
personalization, learning, external-editor read or network port. Search/session
invalidation closes the draft and rejects delayed preparation results. Expanded
panels remain inside the existing IME window and its visible touch region; no
application-overlay permission is used for handwriting or panel expansion.

Repeated internal selection imports revoke the old draft and authentication before
starting a fresh review. Management additions keep mutable text only through
authentication and the bounded background write, clearing it on cancellation,
rejection or completion. AI storage rejects duplicate fields and missing required
current-format fields without replacing the encrypted data. These controls do not
change stored formats or grant access to other applications' clipboard contents.


AI 模型列表仅在设置页点击“获取可用模型”后请求，必须已开启 AI 与联网开关。
请求只携带当前填写的 API key，不发送输入、草稿、历史或附件；仍由唯一 HTTPS Provider 执行，禁止重定向。
远端列表有大小与数量限制，只使用模型 ID，不据此开启图片或音频权限；勾选并保存后才更新加密配置。
模型检测必须收到真实完整回答才显示可用；HTTP 200、HTML 页面或模型列表成功不代表生成可用。
修改配置、退出页面、退到后台和清除数据使旧检测或列表结果失效。排队请求绑定原配置，不能使用后来切换的密钥。

AI 面板快捷切换仅能选择当前 Provider 已保存的模型，并清空草稿、附件和旧会话上下文。
图片与音频能力以实际请求的模型校验；新会话与切换模型均撤销旧请求及延迟结果，不自动发送。
流式成功必须同时收到正常文本结束与 DONE；工具调用、结束后续写、多候选和缺少结束标记均拒绝。


页面文字引用是用户于 2026-10-07 批准的独立默认关闭能力，见
[ADR 0019](docs/adr/0019-explicit-page-references.md)。仅在合格输入会话点击“引用页面”后读取
源应用当前可见的非编辑无障碍文字，默认不勾选，用户选择并点击发送后才进入唯一 AI Provider。
受系统绑定权限保护的服务只监听窗口元信息以撤销旧内容，不后台读取正文、不截屏、不执行手势，
不读取系统/安全剪贴板。密码、编辑节点、不可见、被遮挡及平台标记敏感的内容排除；普通文字仍
可能含敏感信息，页面是否完整公开及敏感标记是否正确取决于源应用。
未选快照 30 秒到期，复选框只显示有界预览；切换来源、输入会话/面板/会话/模型、锁屏、撤权、设置或清除数据使旧请求失效。
窗口状态或窗口集合变化都会撤销旧引用，即使来源标识被复用；事件回调不枚举窗口或读取节点。
显式取消生成也清除所选历史与引用，普通草稿编辑仅停止输出。发送及插入前再次检查代次。
来源标识不离开设备，引用不能成为系统角色或触发工具。
原始引用不落盘，但用户开启会话保存后，保存的提问与回答可能包含引用信息。
会话重命名沿用加密格式；上下文逐条选择独立于历史存储，超限不静默截断，过期操作不能恢复已删除数据。


## 2026-10-08 review hardening

The independent **Clear secure clipboard** command permanently deletes all private
vault entries and their body/index keys after explicit confirmation. It works while
the feature is disabled and never reads content or grants read access. Turning the
feature off retains entries; clearing personalization keeps its stated scope.
Deletion invalidates old work before waiting for storage and reports incomplete
cleanup; retry is required after failure. Management authentication drafts expire
after 30 seconds, with worker-side deadline checks as well as timer cleanup.

Language packs reject special ZIP entry types, including symlinks, and reject a
whole dictionary when any declared payload fails bounded UTF-8/hash validation.
ZIP64 and multi-disk archives are unsupported. AI context reference rows use short
previews unless the user explicitly opens full preview; leaving the pane removes
that full display. Immutable JVM strings and platform copies cannot be securely
wiped. No new network path, permission, component or storage format is introduced.
