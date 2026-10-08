# Threat model

## Protected assets

- 用户输入习惯、词组、词频和 emoji 最近使用记录。
- 安全剪贴板中的私密片段。
- 当前敏感输入会话中的文本。
- 本地导入的语言包和用户主动导出的数据。

## Enforced controls

- Manifest 只为用户主动开启的 AI 网络工作台声明 `INTERNET`；CI 扫描项目源码中的联网权限和
  系统剪贴板 API，并解析 Debug/Release 合并清单执行权限白名单校验，防止依赖间接引入网络、
  存储或其他敏感权限。网络传输仅允许 `OpenAiCompatibleProvider`，只接受 HTTPS、禁止明文、
  查询参数、片段、用户信息和重定向，并限制请求、超时、SSE 单行及整体响应大小。系统剪贴板
  正文读取始终禁止；仅专用防护适配器可在用户开启后监听、检查时间戳和清理。AndroidX 文本控件
  只可能在用户明确执行标准粘贴操作时进入系统编辑路径。
- 除启动页、输入法服务和只接收待确认文本的剪贴板导入页外，Android 组件均不导出；输入法服务只使用系统要求的绑定权限。导入页不能查询或返回私有库内容。
- `allowBackup=false` 且不启用数据提取规则。
- 密码、PIN、可见密码及应用请求的无个性化输入上下文强制禁用学习和个性化数据读取；邮箱、
  URI、隐身模式及用户关闭学习时同样不读取个人词组或 emoji 历史。运行中收紧隐私设置会立即
  取消当前组合状态。
- `TYPE_TEXT_FLAG_NO_SUGGESTIONS` does not disable public Chinese conversion in
  known non-sensitive text fields. Personalization reads/writes, emoji history,
  model context and next-word predictions remain disabled. Password and unknown
  field classification still takes precedence.
- The preparation notice contains only static resource text,
  never editor input, and owns no editor connection or pending key replay.
- Prediction permission travels with the immutable editor context and prepared
  engine identity. `NO_SUGGESTIONS` suppresses public English completions in both
  built-in and language-pack engines while preserving Chinese conversion. A
  no-personalized-learning request alone still allows public completions.
- Native readiness is verified off the IME thread using fixed public input and
  candidate selection, with learning disabled and no editor connection. The
  probe is reset before handoff and never logs input or candidates. A missing
  dictionary fails readiness even if librime can create a session; the private
  data boundary is unchanged and the memory-only fallback remains usable.
- Double-pinyin schema generation uses only the bundled public syllable list on
  the engine worker. No typed text is saved in schema files. Scheme changes use
  the existing idle-composition handoff; native verification uses fixed public
  input. Kaomoji injection reads only a bounded public keyword index plus an
  already permitted personal snapshot. Privacy tightening removes personal
  candidates, and a stale personal candidate is rechecked before selection.
  Password and unknown editors continue to bypass composition candidates.
- 常规用户数据和安全剪贴板使用不同的 Keystore 密钥。
- librime 内建用户词典关闭，候选学习只通过加密的 `PersonalizationStore`。
- 安全剪贴板默认关闭；读取由用户点击发起并要求系统身份认证。
- 私有片段粘贴采用认证与返回后确认两阶段。系统认证导航仅保留条目 ID、原编辑器公开标识、
  删除代次和未消费授权，不保留正文或旧输入连接。返回源应用后用户必须在 30 秒内确认，
  才绑定当前会话、实际 `InputConnection` 与交互序号并读取正文；读取前、完成后再次校验。
  临时凭据编辑器不参与绑定。返回绑定后的输入、会话/面板切换以及设置或默认输入法变化使
  待确认操作失效。字段 ID 和包名仅限制返回范围，不能证明编辑器身份，因而必须明确确认。
  确认后收起键盘或收到意外选区变化同样取消后台读取；仓库取得串行锁后以及解密完成后再次
  检查取消状态与删除代次，避免等待锁期间失效的请求仍读取正文。
- 安全剪贴板不向 Android 系统剪贴板同步内容，也不导出内容 Provider 或管理组件。
- 未认证面板只读取独立的无标签索引；安全剪贴板正文不会为生成列表摘要而解密。
- 安全剪贴板正文解密、索引读取和 emoji 历史读取均在 IME 主线程之外执行；仅无正文的展示模型
  缓存在进程内，并在会话或隐私状态变化时隐藏或清空。清除个性化数据会使已排队的 emoji
  历史写入失效；会话或隐私策略变化同样会使已排队的词组、词频和 emoji 写入失效。用户主动
  清除会删除用户词组和 emoji 历史的专用 Keystore 密钥，实现密文与密钥一并擦除。
- 输入会话首帧只使用轻量内存引擎；Rime 会话和语言包词典由共享有界后台队列创建、启动。
  会话初始语言从当前 ZeroInput subtype（`zh-CN`/`en-US`）解析，回调缺失或乱序时才使用本地
  语言设置，避免系统显示的 subtype 与实际引擎状态分离。
  键盘上的语言切换同步系统子类型；系统回调仍执行交互失效和个性化任务取消，旧认证结果不能
  因语言恢复或窗口重建而继续提交。
  已准备引擎必须匹配会话令牌、编辑器包名、语言包键和隐私快照。通常只在无组合文本时接管；
  内置轻量全拼降级引擎的未分词纯拼音组合可由已准备的全拼引擎恢复。恢复失败、已选分词、语言包
  或不支持组合恢复的引擎均保留当前组合；
  会话切换、隐私收紧或任务取消会关闭过期 native/data 引擎，避免跨编辑器状态泄露和句柄泄漏。
  后台结果在投递到 IME 线程前由生命周期所有权交接器托管；服务销毁、Handler 回调移除或投递
  拒绝时会关闭仍未交接的引擎。
- 所有应用层后台队列均有明确容量，取消时移除排队 Future；队列满时不回退到 IME 主线程，
  仅丢弃可重试的预热、历史刷新或可选学习任务。用户主动清除个性化数据会先清理排队的旧
  操作并串行执行删除；若耐久清除提交被拒绝，只允许设置页后台线程作最后一次同步重试。
- 导入器对归档和展开内容限制文件大小、条目数、路径、扩展名和校验和；manifest
  通过有界字节流严格按 UTF-8 解码，实际读取字节仍会在激活前复核，防止 zip slip、zip
  bomb 和压缩条目元数据失真。
  替换已安装语言包时，只有成功暂存旧包后
  才允许恢复备份；暂存失败保留原目录，新包激活后的备份清理失败不会回滚有效新包。
- release 构建关闭调试，源码不得记录输入文本。
- AI 工作台默认关闭，只有用户同时打开 AI 总开关和联网开关后才可发起网络请求。请求只携带
  用户在 AI 面板主动提交的文本与用户明确选择的会话历史；不读取编辑器周边内容、选区、系统
  剪贴板、安全剪贴板、个人词库、emoji 历史或其他输入历史。密码、PIN、邮箱、URI、隐身、
  未知或隐私收紧的输入会 fail-closed。流式结果只显示在面板，必须再次点击“插入结果”才可
  写入当前编辑器。
- AI 配置（含 API key）与可选会话历史使用相互独立的 AES-256-GCM/Keystore 别名，位于
  `noBackupFilesDir`；会话保存默认关闭。API key 不进入日志、Intent、异常文本或诊断。会话、
  设置、输入法服务销毁、清除 AI 数据或编辑器切换都会使旧请求和排队写入失效。
- AI 配置的发布与撤销使用同一短锁及独立版本，过期读取和保存不能重新启用旧联网配置；
  编辑器隐私变化只撤销请求和数据任务，不会误丢初始化配置。回归测试覆盖撤销后旧读取、
  新配置生效后旧保存以及编辑器代次独立性。
- AI configuration persistence rejects invalid ports and API-key control characters
  before replacing saved data, matching the transport validation. A rejected edit
  retains saved credentials while the UI keeps activation switches off. Empty or
  oversized completion events cannot become insertable results. Regression
  coverage is recorded in [the code audit](code-audit-2026-09-28.md).
- Debug-only 输入诊断只显示编辑器包名、公开 `EditorInfo` 类型/选项、subtype 及当前会话的
  隐私、语言和引擎状态；不显示输入文本、拼音、候选词、周边文本或异常堆栈，不写入日志/文件，
  会话结束时清除。Release 构建不渲染该诊断。

## Review follow-up controls (2026-09-28)

- Authentication results remain cancellable until the owner-thread callback is
  consumed. Cancelling or timing out after success was queued delivers denial
  once; duplicate or late completions cannot revive the grant. Regression tests
  exercise the real Android Handler without launching or bypassing authentication.
- Private clipboard management dialogs protect their own windows with FLAG_SECURE;
  the Activity flag alone does not protect a dialog window. Dialogs and metadata
  views disable state saving, autofill and content capture. Dismissal clears editable
  drafts, and stopping the page dismisses private dialogs. Tests use public fixtures.
- AI SSE responses reject non-string content and explicit truncated, filtered or
  tool-call finish reasons even when followed by DONE. Such output is not an
  insertable or persistable completion. Conversation titles do not split UTF-16
  surrogate pairs at the existing length bound.
- Language-pack manifest and JSON dictionaries are checked before recursive
  parsing for a maximum of 16 container levels and standard JSON tokens. Bare
  keys, single-quoted strings, comments, NUL and trailing documents are rejected.
  JSON dictionaries use bounded strict UTF-8 decoding; invalid JSON cannot be
  reinterpreted as a line dictionary or activated alongside otherwise valid rows.
- Optional ranking worker-construction failures wipe pending owned inputs and
  reset scheduling state, allowing later retry without retaining model context.
- Settings mutations report content-free failures for storage errors and rejected
  execution. A destroyed page cannot receive pending callbacks; an error cannot
  produce a success notification or expose a parser exception to the user.

## Encrypted storage failure boundaries

- AI configuration and conversation readers reject duplicate object fields and
  missing required current-format fields. Invalid data cannot silently become
  default configuration or empty message history and is never rewritten on read.
  Strict UTF-8 decoding owns its mutable buffer before decoding, including partial
  failure; that buffer and caller-owned decrypted bytes are cleared on exit.
- A repeated internal selection import first revokes the previous draft, grant, callbacks
  and timeout, then starts a fresh review. The replacement requires its own
  authentication and explicit save; invalid or replayed selection tokens fail closed.
  Repeated external exported imports retain their reject-and-close behavior.
- Management additions retain one owned mutable draft until a background write
  consumes it. Authentication denial, executor rejection and Activity destruction
  cancel and clear pending drafts. In-flight writes recheck cancellation through
  the vault port and clear their buffer on completion without blocking the UI.
  Regression tests cover delayed writes, cancellation, write failure and repeated
  selection import. JVM/JSON strings still cannot be reliably erased.

- Ciphertext reads require the existing dedicated Keystore key. Missing keys,
  invalid envelopes, wrong AAD and failed GCM authentication cannot produce
  plaintext or silently create a replacement key. Envelope format 1 is unchanged.
- One-use authorization is consumed atomically, including failed expiry checks.
  Callers cannot extend the 30-second maximum. This remains an application-layer
  gate; Keystore keys are not newly bound to biometric/credential authentication.
- Unreadable existing files are not treated as an empty repository. AtomicFile
  commit and deletion results are checked for surviving base/backup/new files as
  appropriate. Failed file deletion still attempts key deletion; no error claims
  a successful erase. Owned plaintext and intermediate byte buffers are wiped.
- Vault JSON errors use content-free messages without parser causes. Malformed
  input cannot be silently replaced by a new empty vault. JVM strings and parser
  objects remain subject to garbage collection; complete memory erasure is not
  guaranteed.
- Clear revokes queued operations before waiting for active storage work. Both
  body and index deletion are attempted, and incomplete clear blocks access until
  retry in the current instance. Metadata/removal/addition recheck the deletion
  generation after storage work; management tasks capture it before queuing and
  Save captures it before authentication. Failed index refresh hides stale rows
  until authenticated reconciliation.
- Body and index are separate atomic files, not a cross-file transaction. Pending
  deletion and index-repair flags are not persisted. Process death after partial
  failure can leave a stale body-free index or require another explicit clear;
  this change does not claim crash-proof erasure or durable rollback.
- Negative tests cover missing keys, malformed/tampered envelopes, failed atomic
  commits/deletes, buffer cleanup, grant races, failed clear/retry and concurrent
  clear against metadata/removal. Only public fixtures and test-only key aliases
  are used. API 26 AtomicFile behavior, physical biometric/key invalidation and
  power-loss recovery remain unverified. See [validation](security-storage-validation.md).

## Input configuration and interaction controls

- Explicit last-word reselection retains at most one 128-unit Chinese draft in
  mutable buffers, within the current session. Another edit, cursor/selection
  change, settings/session invalidation, destruction or the 30-second timeout
  clears it. Reopening requires the original connection, acknowledged collapsed
  selection, no current composing region and exact preceding text. The adapter
  uses `setComposingRegion`; it never independently deletes an assumed word.
  Uncooperative editors fail closed. Android offers no atomic cross-process
  compare-and-replace, so a malicious editor is outside this guarantee.
- Candidate history is bounded and selection verifies the restored page before
  submission. Personal pagination stays on the existing generation-checked
  encrypted worker; a changed data revision discards retained pages even if the
  replacement query is pending. Clear makes unavailable pages ready-empty
  immediately, before its worker executes. A fixed partial phrase suppresses whole-input personal
  overlays, preventing a candidate from unexpectedly replacing chosen segments.
- Default-off typo rules and related-reading enumeration use only public Rime
  resources. Temporary alternate input belongs to an isolated session and is
  cleared on reset/close. No coordinates, key history, model context, private
  training corpus or new permission is persisted. Canonical reading metadata
  prevents learning corrected output under the misspelled input.

- Chinese preferences are immutable snapshots included in warm-up identity.
  Changing options invalidates old preparation results and authenticated actions.
  Adopting a prepared engine cancels queued editor writes before ownership changes.
  An unused paste consent has no engine state and still requires a fresh user tap;
  neither engine adoption nor an idle native-session release can trigger a paste.
  Non-private settings wait for the current composition to finish; privacy
  tightening still cancels composition and personalization immediately.
- Generated Rime schemas contain only allowlisted switches and bounded pinyin
  rules. They are created with a structured parser under noBackupFilesDir and
  compiled on the bounded worker. Deployment never overwrites an index used by a
  live native session; unused generated schemas and prisms are removed. User
  dictionaries remain disabled in every generated schema.
- Simplified/traditional conversion uses bundled, hash-verified OpenCC data.
  Converters are initialized off the input thread. Personal suggestions are
  converted only when personalization is permitted; conversion failures hide
  those suggestions, and do not expose the underlying error or text.
- Retry uses the lifecycle-owned preparation queue. Delayed completion after a
  configuration/session change is rejected. Tests cover independent fuzzy flags,
  disabled abbreviation/fuzzy behavior, delayed old configurations, and a live
  session preventing deployment.
- Candidate clicks carry their original binding generation. Removed views release
  candidate text. Backspace timers stop on release, cancellation, panel changes,
  privacy changes, input-view closure and session end. Clearing composition cannot
  continue into committed editor text during the same hold.
- Performance instrumentation is device-test-only and uses fixed public fixtures.
  Reports contain durations and sample counts, never editor text or personal data.
- Engine selection and Chinese layout are included in preparation identity.
  Delayed results from another engine or layout are closed. The offline dictionary
  engine loads only bundled public data and uses the existing encrypted
  personalization port; it has no private dictionary, downloads or logging.
- Nine-key reading replacement accepts only bounded Latin letters, telephone
  digits and apostrophes across JNI. Reading history is bounded to 64 choices,
  scoped to one engine and cleared on reset/commit/close. A fixed partial
  candidate cannot be overwritten by reading replacement. Sensitive editors do
  not instantiate a nine-key engine. Literal numeric input follows the same
  editor privacy checks as ordinary keys.
- New debug fixtures are nonexported and save no editor state. Layout images are
  generated only by instrumentation from constructed public fixtures; runtime
  input is never captured. Tests cover overlapping pointers, cancellation,
  literal digits, rapid input and delayed old engine/layout preparation.

## Editor and user lexicon hardening

- Panel Back navigation and expression category gestures use the existing
  interaction callback, revoking pending authenticated actions. Predictive Back
  callbacks unregister on root navigation, view release and window detach.
  Horizontal drags cancel candidate/expression clicks and reject cancelled or
  multi-pointer streams; empty private-expression states do not expose data.
- External cursor changes end the visible preedit without reading surrounding
  text, learning its contents or replacing the new selection. Engine/candidate
  state is reset so a later Backspace cannot use the old composing position.
- The fuzzy master updates the existing boolean and rule mask together: all pairs
  on enable, no pairs on disable; individual selection remains supported.
  It uses existing configuration generations and never changes privacy eligibility.

- Editor classes and variations have an explicit allowlist. Unknown classes,
  `TYPE_NULL` and unknown variations are treated as sensitive before any user
  preference can allow suggestions or learning. Session replacement clears old
  composition/candidates and routes basic editing without creating an engine.
- User dictionary import has a 5 MiB byte limit, 20,000-term limit, bounded scalar
  lengths and a fixed three-container JSON structure. Unknown/duplicate fields,
  nested scalar values, trailing documents, malformed UTF-8 and invalid language
  names fail closed without retaining parser messages or causes. All validation
  and merge-capacity checks finish before any persisted or cached data changes.
  Automatic learning also rejects malformed UTF-16 before serialization, so cached
  text cannot diverge from the UTF-8 data restored after restart.
- Imported IDs cannot identify a different local candidate. Matching phrase
  identities preserve their local ID; new identities receive a fresh ID.
- Repository writes publish memory only after encrypted persistence succeeds.
  Clearing invalidates older waiting operations before serializing with in-flight
  writes, then removes the ciphertext and key. Read and write byte buffers are
  cleared in finally blocks; failed reads never become an empty cached dictionary.
- User-initiated phrase and personal-expression exports contain only their
  validated local data and are encrypted with separate Android Keystore export
  aliases. The formats are device-bound: clearing app data or deleting the
  corresponding alias makes an existing export unrecoverable. A confirmation
  describes scope and overwrite behavior; existing destinations are explicitly
  truncated only after encrypted data is available. Document providers can
  expose/synchronize a user-selected destination outside ZeroInput's control.
  ZeroInput itself never uploads or shares the file.
- Device tests use in-memory fault injection and isolated fixture directories/key
  aliases to verify capacity, malformed imports, failed writes, clear/write races,
  ciphertext truncation/tampering and missing keys. Layout images contain only
  constructed public fixtures, not personal dictionary contents.
- A failed dictionary deletion blocks further reads and writes until deletion
  succeeds, preventing a false empty export or a later update over incomplete erasure.

## Private text import boundary

- The keyboard copy action reads only the active nonsensitive editor's explicitly
  selected range. Known and returned lengths must match and remain within 8192
  UTF-16 units. A bounded worker avoids blocking keys. Session/connection, selection,
  interaction and settings changes cancel acceptance, including late IPC responses.
- A frozen selection transfers once to a nonexported page through an opaque token;
  the process-local slot expires after five seconds. No plaintext enters an Intent,
  saved state or disk draft. The receiving page owns the captured import independently
  of subsequent editor navigation, requires authentication then foreground Save,
  and never rereads or writes back to the source editor. The original vault deletion
  generation travels with the draft, so a clear during copying prevents a later save.
  See ADR 0012. No vendor history erasure capability is inferred from these changes.

- The exported text import Activity treats every Intent as untrusted. It accepts
  only PROCESS_TEXT/SEND text/plain payloads of at most 8,192 UTF-16 code units;
  malformed, blank, NUL-containing, URI/stream and unsupported payloads fail
  closed. It discards spans and never resolves providers, links or attachments.
- Incoming calls cannot read vault labels, entries or authentication state.
  Authentication and a subsequent foreground Save confirmation are both required.
  Repeated clicks/callbacks cannot save twice. A callback while the host is covered
  cannot write. Backgrounding outside authentication, new Intents, settings changes,
  recreation and destruction invalidate the request and clear mutable drafts.
- FLAG_SECURE, excluded recents and disabled view/content capture protect the
  confirmation surface. No draft is placed in saved instance state or results;
  launch Intent references are cleared. The authentication flow has a timeout.
- Single-task launch mode bounds the import flow to one screen. A new external
  launch cancels the existing draft instead of replacing an authenticated payload.
- The serial vault checks cancellation and deletion generation before reading
  existing content and before committing an addition. A clear invalidates queued
  imports and deletes after any already committing write. Invalid/corrupt data,
  missing keys and rejected execution never become an empty vault to overwrite.

- AI conversation history is optional and is cleared whenever the saved-history
  setting is disabled, including on the storage worker's startup reconciliation.
  The encrypted history key is deleted with the file; a failed purge keeps the
  repository unavailable until an explicit retry succeeds. A transient in-memory
  conversation may continue only for the current workbench session and is never
  written while persistence is disabled.
- Selection menu availability belongs to the source application. The system IPC,
  source application's own storage and text already submitted to a destination
  remain outside ZeroInput's private storage boundary. See ADR 0006.

## Optional system clipboard cleanup boundary

- Monitoring and all active behaviors default off. Only the approved adapter may
  register callbacks, inspect timestamp/empty metadata and clear the clipboard.
  It never requests text, labels, source packages or URIs. The guard cannot query,
  decrypt or populate the private vault. Metadata is not persisted or logged.
- The new POST_NOTIFICATIONS permission serves an independently enabled generic
  reminder only. Permission denial and disabled channels are visible in settings.
  Notifications hide on the lock screen and contain no clipboard preview. Their
  immutable PendingIntents open nonexported pages and cannot directly clear data.
  A bounded channel, notification and cancelled stale intents prevent accumulation.
- Automatic cleanup requires an explicit mode choice and destructive-effect
  warning. Authentication forces confirmation mode; a biometric/credential result
  alone cannot clear. Confirmation carries an opaque, in-memory ticket and one-use
  grant, checks current metadata again, and expires on cancellation, new clipboard
  content, changed settings, recreation or leaving the page outside authentication.
- Background operations check default-IME identity and a live service. Foreground
  operations instead require a revocable window-focus lease and monitoring opt-in.
  A page cannot grant background eligibility; losing focus revokes queued work.
  Disabling
  monitoring, changing options, detaching the service and default-IME changes
  revoke the worker lease. Queued and delayed work cannot retain authorization.
  Automatic startup processes the existing current item; other modes establish a
  baseline without deletion. Duplicate
  callbacks and empty updates do not cause repeated clears.
- An explicit foreground inspection can issue a fresh, user-requested ticket for
  an existing item even when no callback arrived. Inspection never auto-clears;
  confirmation and optional authentication remain required. Cancelling the page,
  settings changes or default-IME changes invalidate queued inspection results.
  Locked devices and explicit platform denials are distinguished from generic
  failures. A null metadata response is described as empty or unavailable, since
  public APIs do not consistently distinguish an empty clipboard from denial.
- After explicit automatic-mode consent, the focused settings page can request
  processing of the existing current item without another confirmation, including
  with another keyboard selected. This request still requires the foreground lease
  and current automatic options. Authentication cannot be combined with this mode.
- An explicit keyboard private copy is an equivalent automatic-mode authorization
  moment. After the selection snapshot is captured for the private vault, the IME
  queues one silent inspection that clears a stale current item only under
  current automatic options. It holds an input-session lease, carries no captured
  text or grant, and an editing-context change revokes it before execution.
  Without monitoring opt-in or under any non-automatic mode this path leaves the
  current item untouched, which the platform regression suite asserts.
- Cleanup feedback reports a request for the current item and explicitly states
  that history was not deleted. The confirmation and automatic-mode warnings name
  system/keyboard history, pinned items and cloud copies. The platform clear API
  returns no success receipt; a null metadata result is not proof of erasing any
  independent history. No extra permission or cross-application deletion is added.
- Android offers no atomic compare-and-clear. Timestamp checks reduce stale
  requests, but timestamps may collide and a new write between check and clear may
  also be erased. Callbacks cannot distinguish intentional Copy from an accident.
  Process death, platform access restrictions and vendor lifecycle policies can
  delay or prevent callbacks. Cleanup cannot stop access at the instant of writing,
  recall prior reads or erase other clipboard histories. Random overwriting adds
  no such protection and is not used. API 26-27 clear with literal empty text.
- Negative tests cover default-off, cancellation during blocked work, service and
  default-IME changes, stale tickets, authentication denial, duplicate callbacks,
  unavailable APIs, disallowed source access and arbitrary writes. Device tests
  use synthetic metadata or a single fixed public fixture, skipping platform
  mutation when existing clipboard metadata is present. No real clipboard body
  is read or included in tests, snapshots or diagnostics. See ADR 0007.
- Platform mutation tests establish a visible, real ZeroInput IME before enabling
  monitoring or writing their public fixture. Bounded input reconnection requests
  exist only in the test driver; they do not bypass the production service lease.
  Regressions verify that an unattached service cannot subscribe and that cancelling
  confirmation or disabling monitoring preserves the current public fixture.

## Optional application overlay

- SYSTEM_ALERT_WINDOW is user-approved solely for a separately enabled, default-off
  clipboard reminder. A reason-and-limit dialog precedes opening system permission
  settings, including retries. Cancelling education never enables the option or
  launches the system page. Permission denial leaves the reminder unavailable.
- The window contains generic status, a dismiss control and a navigation action.
  It never contains clipboard text, source names, authentication state or selected
  page text. FLAG_NOT_FOCUSABLE avoids capturing input and FLAG_SECURE prevents
  screenshots of the live window. Its touch region is bounded to its visible box.
- Only one window exists; events coalesce by opaque identity and time out after
  3, 5 or 10 seconds. Settings/lifecycle invalidation, lock screen, permission
  revocation, dismissal and entering a ZeroInput Activity remove the window.
  Test preview is an explicit exception for the nonsensitive guard settings page.
- A tap navigates to the existing secure confirmation page; it cannot issue a
  cleanup or authenticate in the background. There is no new exported production
  component, foreground service, accessibility service, polling or full-screen
  notification. System/OEM hiding and background lifecycle restrictions still
  apply. The permission cannot recover missing clipboard callbacks or erase other
  applications' histories. See ADR 0008.
- The instrumentation APK has a separate-UID source Activity containing only a
  fixed public fixture, native selection/copy and a text-share action. It is absent
  from both production variants. Tests never inspect another app's private data.

## Personal expression controls

- Favorites, custom text, names and keywords use a dedicated AES-GCM file/key
  under noBackupFilesDir. They are excluded from phrase exports and Android
  backups. The new management Activity is nonexported, excluded from recents and
  FLAG_SECURE; it disables autofill, content capture and saved drafts.
- The fixed binary decoder limits bytes, entries and every UTF-8 string before
  allocation. Invalid lengths, malformed UTF-8, duplicate entries, controls,
  trailing data and unknown versions fail closed with content-free errors.
- Reads and writes use background workers. A snapshot/operation must match the
  session/privacy generation at execution and delivery. Custom selections must
  also match the current library revision. Ending the input view clears personal
  rows, queries and caches; stale views and delayed callbacks cannot expose them.
- Explicitly managed entries obey the same IME privacy policy as recent history:
  passwords, PIN, email, URI, no-personalization flags, incognito and disabled
  learning hide all personal expressions and prevent IME history/favorite writes.
- Clear personal data invalidates queued mutations and deletes the new file/key
  as well as phrases/history. Failed deletion blocks access until retry. History
  format 1 is preserved with a larger text bound and strict decoding. Unsaved
  mutations cannot appear as successful history; old custom history values are
  not offered after the associated custom entry is removed or edited.
- No system clipboard, authentication grant, runtime network, exported component
  or permission is added for expressions. Public source data and notices are
  bundled and hash-checked during the build. Keystore fault, cancellation,
  clear/write race and privacy-revocation tests use constructed public fixtures.

## Keyboard appearance and editor layout

Appearance preferences contain nonsensitive style values and an opaque image UUID.
An explicit system-picker grant permits one content URI; no URI is retained and no
storage permission or app network transport is added. The picker requests local
sources; a document provider's own synchronization remains outside app control.
Raster decoding accepts only PNG/JPEG/WebP signatures, at most 12 MiB, 40 million
source pixels and a 16384-pixel edge. Sampling caps the decoded edge at 1280 pixels.
AndroidX EXIF orientation parsing and re-encoding strip metadata before persistence.
The byte count is enforced against the stream, independent of provider metadata.
Malformed, truncated, oversized, denied, stalled and cancelled imports fail closed
and preserve the previous selected image. Decode/blur/storage run on a bounded serial
worker; a 15-second UI deadline and descriptor cancellation revoke stale publication.
A provider or native decoder that ignores cancellation can occupy that single worker;
queue limits prevent unbounded work, and ordinary keyboard input remains usable.

Each image revision is atomically encrypted with AES-256-GCM and the isolated
`zeroinput.keyboard-background.v1` Keystore alias under noBackupFilesDir. Ciphertext
size is checked before loading; tampering, truncation or key loss falls back to the
palette. No original file, EXIF, source URI, path or pixels enter logs, preferences,
AI, exports or backups. Removing/resetting the background revokes old imports and
serially deletes files and the dedicated key; failure is shown and can be retried.
Unpublished encrypted revisions are removed on cancellation or subsequent startup.
Temporary byte/pixel arrays are wiped; exclusively owned display bitmaps are released
on view hide/replacement/destruction. Native decoder/renderer copies cannot be reliably
zeroed; the chosen image is intentionally visible as keyboard decoration to anyone
looking at the screen. The secure settings window prevents system previews/screenshots.

The preview has no editor, engine or personal-data callbacks. Appearance changes use
the existing invalidation for pending authentication/personalization. Opacity affects
only the background within the opaque IME surface, never the labels or other apps.
Frosted glass uses only the keyboard's own background and captures no screen content.
Numeric layouts and conservative editor privacy classification remain unchanged.
Tests render only synthetic public images and nonexported Debug fixtures.

## Experimental model context

Offline handwriting uses only the user's current touch strokes and fixed public
model assets. It does not read editor text, clipboard, personal dictionaries or
history, and does not learn or transmit strokes even for sensitive editors. The
panel stores bounded strokes only in memory; cancellation, panel/session/settings
changes invalidate queued work and stale results. Application-owned buffers are
wiped, but native allocator copies cannot be verified as fully erased. Only a
size/hash-verified public model is cached under `noBackupFilesDir`. A failed model
shows an unavailable state and cannot insert an old candidate into a new editor.
See [ADR 0016](adr/0016-offline-chinese-handwriting.md).

The default-off short-word scorer adds at most 16 transient Chinese characters
successfully committed by this IME in the current editor. It never queries the
editor, clipboard or stored personal history for context. Sensitive, unknown,
identifier, incognito and learning-disabled policies reject context and scoring.
Private snippet and emoji entry paths invalidate context before direct commits.

One bounded worker receives owned character buffers, later replaced by token IDs.
Session/view/settings/cursor/reconversion/privacy changes revoke generations,
wipe pending buffers and clear context. Running inference may finish its bounded
16-row workload, then wipes inputs and discards stale outputs. Only revision and
winner index reach a coalesced main-thread delivery; core rechecks eligibility and
candidate routes. User browsing and held touches suppress promotion. No context,
candidate, score or token ID is logged, saved, backed up or sent off-device.

The native session and tensors are explicitly closed. Application buffers are
wiped; the third-party allocator does not provide a verifiable erasure contract
for every internal temporary copy. This is a process-memory limitation, not a
disk cache. Only the fixed public model is copied to noBackupFilesDir. Source,
graph, vocabulary and runtime are pinned; size/hash checks precede activation.
Malformed/missing assets and runtime errors keep baseline input working. No new
permission, component, network path or personal-data format is introduced.

## Offline association context

Coverage expansion uses only project-authored public phrase pairs; no user data,
external corpus, runtime dependency or additional permission is introduced. The
existing row/field/query/candidate limits remain unchanged. One-character Chinese
anchors now require a full-context match, reducing spurious suffix matches.
The developer evaluation uses fixed synthetic fixtures and records only case
identifiers, ranks and counts. Evaluation fixtures and baseline reports are test
resources and are absent from application packages.

The default-on association setting permits a maximum of 32 UTF-16 units from
successful IME commits in the current normal text editor. A borrowed read-only
view is used synchronously against a bounded, immutable public phrase index;
there is no private-context background task. Failed commits, sensitive/unknown
editors, numeric/phone/date layouts, identifiers, no-learning/no-suggestions,
incognito and disabled learning cannot seed or query this context. The feature
never reads surrounding editor text, clipboard bodies or private history.

Session/view/connection, cursor/selection, deletion, language/engine, settings,
panel, reconversion and literal/private insertion boundaries wipe the mutable
buffer and revoke candidate identities. Typing hides old predictions. An old
click cannot accept a replacement candidate; Space/Enter cannot accept an idle
prediction. Selection can only commit the currently visible public continuation.
Late public-index readiness has no session callback and cannot resurrect
candidates.

Association order may follow learned personal frequency (ADR 0014). The lookup
is read-only, covers at most the eight displayed public rows, and is answered
from the prepared in-memory mirror under the same personalization gate as the
strip itself; it cannot enumerate the personal store, injects no private word
into the strip, and performs no disk, keystore or decryption work on the input
thread. An explicit, adapter-accepted click on a visible public continuation
also learns that word through the existing encrypted `PersonalizationStore`
with the session's learning flag — the same channel, gates and deletion
semantics as learning a typed commit. Chinese continuations are learned with
their own text as the shortcut (no reading is fabricated), so they cannot
resurface through pinyin prefix matching; they only feed frequency reranking
and the phrase management screen. Failed, stale or gated clicks write nothing,
and write-back failures are counted without affecting the commit.

The bounded phrase resource is loaded off the input thread. Malformed/missing
data and lookup failures leave ordinary input usable. Context is never persisted,
logged, backed up or transmitted; temporary JVM lookup strings remain subject to
garbage collection, and JSON decoding necessarily creates JVM `String` copies
that cannot be reliably zeroed. No new permission, exported component, dependency or stored
personal format is added. Unit and device regressions use constructed public
phrases. See ADR 0013, ADR 0014 and `word-association-validation.md`.

## Offline glide, placement and RGI additions

Glide input owns only bounded current-touch coordinates and public key geometry.
There is no editor-text lookup, private dictionary access, runtime networking,
gesture training, logging or persistent trace. One worker uses public pinned
English/Rime data. Atomic generation revocation can originate on a data-clear
worker, while UI/replay cleanup stays on the IME thread. Delivery, explicit choice,
each replay slice and final English commit recheck the same session/layout and
actual connection. New touches, panels, settings, privacy, cursor, service/view
and data-clear boundaries invalidate prior choices. Sensitive, unknown and
nontext editors reject the path; no-predictions English editors reject it too.

Floating/single-hand geometry contains no text or application identity. The IME
touchable region is restricted to the visible surface so an empty full-screen
host cannot consume other application's touches. Safe bounds are recomputed after
rotation/resize; malformed stored fractions fail to safe clamped defaults.
Placement controls use the existing interaction invalidation boundary and do not
create any application overlay or permission. All secret-panel protections still
apply to the same IME window.

Official Emoji/CLDR metadata and Noto artwork are public, hash-checked and bundled.
Parsing, filtering and decoding have byte/row/query/queue/cache limits. Stale cell
callbacks cannot replace a newly bound image; stale query results cannot restore
personal rows. Variants grant no personal-data access. Explicit Backspace reads
at most 64 UTF-16 units before the cursor to delete one grapheme/RGI sequence,
without retaining, learning, logging or transmitting that cursor-local text.
The receiver controls its own font. No encrypted-store format, permission or
exported component is added. See ADR 0017 and the scoped validation reports.

## Out of scope

- The initial v0.1.0 prerelease APK is debug-signed and debuggable. Authorized ADB
  debugging can access a debug application's sandbox; this artifact is intended
  for synthetic-data testing and does not carry production-release guarantees.
  Production Release builds remain nondebuggable and unsigned until a maintainer
  supplies a release key outside the repository. Signing keys are never published.
  License/source notices are bundled in APK assets and alongside release downloads.

- root、解锁 bootloader 后的系统级攻击。
- 被恶意系统组件截屏、录屏或注入的输入。
- 用户主动导出文件交给文档提供方或外部同步后的存储安全。
- 目标应用自身读取已经提交给它的文本。


## AI workbench additions

The content review Activity is currently nonexported. Earlier plans described an
external SEND/PROCESS_TEXT route; its parser remains defensive, but the manifest
does not publish that route. Intent actions, MIME and text length are validated; arbitrary
URIs/streams from senders are never opened. The Activity shows a protected review
and uses only OpenDocument grants for attachments. It has no network or editor write
port and always returns RESULT_CANCELED. A confirmed draft expires after two minutes
and requires another explicit claim in an eligible IME session. Password, PIN, email,
URI, unknown and privacy-tightened sessions cannot claim, submit or insert content.

Malicious document providers can lie about length/type or stall reads. Actual byte
count and strict text decoding are enforced, reads run on one bounded worker and
cancellation closes the active descriptor. Unsupported types, too many/large files,
invalid UTF-8 and stale callbacks fail closed. Attachments are transient, excluded
from saved conversations and wiped on removal/session revocation. Provider processing
of malformed media is outside this application's control; no local media decoder runs.
The OS-selected document provider may itself synchronize remote files; this is not
an additional app network transport. New tests cover import validation, explicit
confirmation, expiring one-time ownership and editor privacy transitions.

Multiple provider profiles use the dedicated encrypted configuration store. Missing
selection cannot fall through to another profile's credentials. Image/audio capabilities
belong to the selected model, and mismatches are rejected before opening a connection.
Settings revoke queued network requests and imported content. Failed saves preserve
the protected edit draft and leave networking revoked until a successful explicit save.

See [ADR 0015](adr/0015-ai-workbench-boundary.md). The AI draft uses a separate
local conversion session and an empty personalization port. It has no external
editor or clipboard read port. Email, URI, no-personalization,
incognito and learning-disabled sessions are rejected alongside sensitive inputs.
Only ordinary NO_SUGGESTIONS text editors may use independent AI drafts; every
combined restriction is evaluated before granting this exception. Public conversion
does not enable personal reads or learning. UI and request checks share the policy.
The always-discoverable AI icon is only an explanation action in those contexts:
it displays a fixed, localized restriction message without opening the workbench,
loading AI history, reading editor/clipboard content or creating a request. The
entry rechecks privacy after the interaction callback, so a concurrent policy
change cannot use a previously available icon to enter AI.

One bounded worker handles transport; a separate serial worker owns encrypted
storage. Stream deltas coalesce into one pending UI callback. Both enqueue-time
and delivery-time generations reject stale results. Malformed or truncated streams
cannot produce an insertable result; duplicate terminal callbacks are discarded.
Settings changes revoke old requests before saving. Clear reports success only
after both stores and dedicated keys are deleted; a failed deletion blocks access
until an explicit retry. All store work stays off the input thread.

Each provider request has one absolute deadline covering queue wait, connect, write
and streaming read. A bounded deadline worker disconnects the active connection;
terminal events are deduplicated. Attachment bytes are owned by the provider after
submission and wiped once, after a running serializer exits or immediately when a
queued or rejected request is removed. The workbench keeps only independent draft
copies and never submits its draft array as provider-owned storage.

The IME verifies the active `InputConnection` before processing selection callbacks
and rejects callbacks whose previous selection cannot belong to the current anchor.
A callback arriving after `onStartInput` is ignored without changing the new editor
state.

The settings model probe reuses the sole transport and sends only a fixed public
prompt to the saved selected model after an explicit click and enabled network
switches. It has no editor, draft, history, attachment or persistence ports. Output
tokens and timeout are capped; dismissal/background/configuration/data-clear revoke
callbacks and cancel transport. HTTP status categories expose no server bodies,
credentials or content. Negative tests cover disabled networking, cancellation,
stale/empty responses and combined editor restrictions.

The bounded encrypted history file is temporarily decoded when listing; only
summaries leave that operation. Selecting a chat loads messages, without a global
plaintext cache. The active chat and draft are released on panel/session changes.
AI and API-key windows use FLAG_SECURE and disable saved drafts/autofill/content
capture. Results are plain text and never execute tools, render HTML or fetch URLs.
Provider configuration fields may remain in their protected dialog while the user
temporarily switches apps to obtain configuration details. This bounded draft
stays only in the live Activity's views; it is neither automatically persisted
nor put in a Bundle. Cancel, page finish or destruction clears the editable
fields. Only explicit Save updates the encrypted configuration. Platform tests
cover background/foreground retention, no implicit persistence, cancellation,
destruction and continued secure-window protection.

Explicitly submitted text and selected history leave the device for the configured
provider. Provider retention, connection metadata, malicious model replies and JVM
string zeroization limits are documented in the assessment. A prompt cannot bypass
local insertion, session checks or clipboard authentication.

## Internal search and panel expansion

Expression search uses an isolated local draft (64 UTF-16 units), full-pinyin or
English conversion, and no personalization or learning. Search keys and candidate
commands never reach the external editor. Selecting an expression still uses the
existing explicit insertion and privacy checks. Leaving search, ending/changing
the editor, hiding/replacing the view, changing settings/subtype/privacy, and
clearing personal data revoke draft work and remove queries. Delayed warm-up
callbacks cannot restore a closed draft. Mutable committed buffers are overwritten;
JVM composition/display strings remain subject to garbage collection. No draft is
saved, logged, sent to AI, or read from an editor/clipboard.

Panel expansion uses the existing IME window and bounded visible touch region.
It adds no overlay, permission or persisted setting. System bars/cutouts and current
parent constraints limit fullscreen handwriting; resizing retains the established
stroke invalidation rule. Candidate prefetch keeps six engine and six personal
pages at most, preserves ID checks, and clears retained personal pages on revision
or privacy changes. Device tests use public fixtures to verify real window growth,
internal search isolation, stale-session clearing, bounded drafts and scroll anchors.

## AI remote model discovery and protocol repair (2026-10-07)

The explicit provider-editor discovery action sends only the draft credential to
the user-entered HTTPS origin. Both saved network switches are required. It never
sends editor text, drafts, conversation history, attachments or stored model lists.
No background discovery, redirects or cross-origin credential forwarding exist.
Changing endpoint/key/model draft, dismissal, backgrounding, settings revocation
or clearing AI data cancels the operation and invalidates queued UI results.

Catalog data is untrusted: at most 512 Ki characters and 2,048 entries, strict
string identifiers of at most 128 characters, no control characters, deduplication,
no executable content or capability inference. Users explicitly select at most 32
models and save; unsuccessful discovery preserves manual choices. HTML success
pages and malformed/oversized catalogs fail closed. Non-streaming JSON generation
requires one completed text choice with a stop finish; partial/tool results remain
unavailable for insertion. Server error bodies are not read or displayed.

A request binds its configuration when queued and cannot acquire a replacement
provider's key later. Queue time consumes the absolute deadline. Cancellation
cannot silently discard other queued operations. Workbench configuration changes
revoke pending delivery, insertion and history writes. Tests cover HTML 200,
authentication failure, redirect rejection, bounded catalogs, cancelled/stale
UI callbacks, replacement credentials and late persistence.

The temporary live fixture is opt-in instrumentation only and receives credentials
through explicit runtime arguments. It uses public text, never persists keys or
responses, and prints only counts/pass categories. Host debugging infrastructure
and the user's temporary credential lifetime remain outside application storage.

The opt-in live settings acceptance fixture additionally exercises the actual
protected settings editor and encrypted save. Its temporary credential exists in
the normal encrypted configuration only during the test; a finally block restores
the original configuration. It requires an emulator without saved AI conversations,
uses public draft text and disabled history persistence, and captures no live
credential/response screenshot. It restores the prior keyboard and privacy settings.

Quick model selection exposes only the current provider's saved identifiers, never
credentials. It starts fresh context, cancels the old operation, rejects queued
UI/history delivery, and clears draft and attachment buffers. A model ID supplied
by the UI cannot select an unsaved model or inherit another model's capabilities.
Configuration/session revocation dismisses stale model menus. New chat is available
during generation and discards pending output without deleting saved conversations.
Tests cover unknown/restricted selections, default restoration, late completion,
new-chat history isolation and effective-model attachment capability checks.

Streaming completion requires both a normal text stop and the DONE event. Mixed
tool/text chunks, content after stop, multiple choices and trailing JSON cannot
create a successful model probe or insertable answer. Bounded multiline SSE events
and usage-only chunks are accepted. Live follow-up tests assert a public word from
prior context, not merely a nonempty response.


## Explicit page references and selected history (2026-10-07)

The approved exception in ADR 0019 adds access to another application's visible
noneditable accessibility labels only after an explicit action in an eligible IME
session. The separate default-off preference and platform accessibility grant are
both required, as are AI/network enablement and existing editor privacy gates.
The system-bound service has no network, file storage, clipboard or editor-write
port. It has no gesture, screenshot, key-filter or scrolling capability.

Protected assets include unselected page labels, transient selected references,
source window identity and the user's question. Malicious source applications may
expose misleading text, huge trees, duplicate blocks or instruction-like content.
Collection excludes nonmatching packages, invisible/editable/password and marked
sensitive subtrees before accessing node text, bounds traversal and selected text,
and does not expand to another window when source identity is uncertain. Blocks
under the keyboard or other higher windows are excluded. Text remains plain data.
Quotes cannot supply message roles, execute tools or change provider credentials;
prompt injection may still influence model output, so no automatic effects exist.

Platform node IPC and Android internals can temporarily hold additional data or
outlive application deadlines. API 33+ disables node caching and prefetch; older
platform copies and immutable JVM strings cannot be reliably zeroed. The app does
not inspect excluded node text, retain nodes after traversal, or persist raw page
references. App code releases snapshot/UI strings on cancellation or expiry. The
application timeout rejects late callbacks without spawning replacement workers.

The broker generation invalidates on settings/data clear, source window changes,
lock, disconnection, IME panel/session changes and chat/model changes. Source events
never read text or enumerate windows. Both window-state and window-set events revoke
review even if a source package/window ID is reused. Unselected snapshots expire in
30 seconds. Generation validity is
checked synchronously before Send and Insert, preventing a queued revocation UI
callback from leaving an old result usable. Repeated capture cancels pending reads.
The source package and window ID remain local and are never sent or logged. Review
text is shown in the checklist only as a bounded preview; the complete snapshot
remains in the binding until explicit confirmation or expiry.
Imported references share the immutable JVM string limitation; temporary mutable
import buffers and attachment bytes are wiped on consumption or rejection. No raw
page or imported reference is added to the saved conversation format.

Context selection has its own revision so stale checkboxes cannot modify a later
conversation. Saved messages are separate from the selected request projection;
opening a saved conversation sends nothing and initially selects no history.
Changing context revokes active output. Over-budget selections are rejected intact.
Only explicit submission sends checked references/history and the question.

Rename preserves conversation format 1; configuration format 4 is unchanged. Reads,
rename, delete and clear retain serialized storage and generation checks. Missing
or failed renames do not recreate deleted chats. Raw references are not persisted,
but ordinary saved questions/answers may include information from those references;
the UI discloses this retention distinction. Source sensitivity labeling and the
provider's retention policies remain outside the application's guarantees.

Tests use fixed public content from a separate-UID test Activity, which is absent
from the main APK. Unit tests cover bounds, cancellation, unchecked data, context
roles, stale revisions, failed rename and clear/delete races. Platform verification
covers actual node filtering, opt-in/disconnection, keyboard layout and draft
isolation. Validation outcomes are recorded in `ai-page-context-validation.md`.
