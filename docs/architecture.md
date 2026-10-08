# Architecture

The public project and application display name is `zero fish input`.
`dev.zeroinput.ime`, Kotlin namespaces, engine resource identifiers, encrypted file
names and Keystore aliases retain their existing identity. Debug uses the separate
`dev.zeroinput.ime.debug` package. App build assets include offline license texts
and source links copied from the repository; they add no runtime network path.

## Dependency direction

```text
app -> ime-core --------> engine-api
 |                         ^
 +-> ime-ui                |
 +-> engine-english -------+
 +-> engine-rime ----------+
 +-> engine-dictionary ----+
 +-> model-scoring --------+
 +-> user-data ------------+
 +-> ai-api (independent contracts)
 +-> language-pack -----> engine-api

user-data -> security
user-data -> ai-api
ime-ui -> ai-api
language-pack -> security
```

业务层不引用 librime 类型。升级、替换或移除 librime 时，变化限制在 `engine-rime` 模块和语言数据部署器内。

## Runtime boundaries

Composition editing and candidate continuation are described in
[ADR 0010](adr/0010-composition-editing-and-candidate-continuation.md).
The optional engine editing port keeps segment selection/undo in engines;
canonical commit readings flow through the existing encrypted learning port.
The core owns a six-page candidate window and one bounded recent-word draft.
Only the Android adapter verifies and reopens an editor composing region.
Rime prepares a secondary shared-dictionary session for related full-keyboard
readings; nine-key retains its original native flow. Personal query pages are
prepared on the existing serial worker and never decrypt on key dispatch.
Microsoft and Ziranma double-pinyin codes are generated from the pinned public
syllable list during worker-side schema deployment. The full-pinyin related-reading
session is not used for double-pinyin codes. The keyboard follows the applied
session scheme while a pending setting waits for the current composition to end.
`ime-core` appends bounded kaomoji candidates from an injected lookup port only
to eligible built-in Rime composition. The app indexes public expression keywords
once and supplies only the currently permitted personal snapshot; candidate taps
recheck identity and personal availability before committing.

Handwriting is a separate single-character panel. `ime-ui` owns bounded touch
strokes, `app/handwriting` owns debouncing and editor-generation cancellation,
and `model-scoring` owns verified public assets, aspect-preserving stroke features,
Tegaki/Zinnia sparse scoring, rasterization, ONNX inference and Han-only fusion.
The app commits an explicitly selected candidate only after
checking the current input connection. No Rime session, personal lookup or learning
port participates. Model I/O and inference run on one bounded worker; see
[ADR 0016](adr/0016-offline-chinese-handwriting.md).

Glide recognition uses an Android-free `engine-api/GlideDecoder` contract.
`ime-ui/GlideTouchTracker` snapshots bounded points and actual key geometry;
`engine-dictionary/DictionaryGlideDecoder` matches public templates on one
application-owned worker. English/Rime adapters load pinned public word/readings
off the input thread; double-pinyin and nine-key reuse the existing mappings.
`app/glide` owns request generations, explicit choice and short main-thread code
replay slices. Every slice rechecks session, connection, interaction and layout.
Chinese readings append through normal conversion; English choices finish a word.
Public lookup never queries editor context or personal data. See [ADR 0017](adr/0017-glide-placement-and-rgi-emoji.md).

`ime-ui/KeyboardLayoutHost` owns a single surface containing all panels, placement
controls and bounded normalized geometry. `app/keyboard/KeyboardWindowLayout`
adapts the IME content/visible/touchable insets. Floating mode reserves no full-screen
content inset and only its visible rectangle receives input; docked/one-hand modes
reserve their measured height. No application overlay window or permission is used.
Only public geometry is stored, separately for portrait and landscape.
Keyboard and candidate layout decisions use the measured IME viewport, including the
current split-screen or freeform width and height, rather than device orientation or
full display size. Docked and one-hand surfaces consume the window's supplied bottom
edge directly; only floating and expanded full-window panels reserve system-bar safety
insets. Narrow viewports retain candidate browsing width by moving secondary actions
into an accessible overflow menu.

The complete Emoji 18.0 catalog and suffix trie are built on a bounded worker from
hash-verified bundled data. UI category lookup uses immutable indexes; text searches
and WebP decoding use bounded workers with generation/cell-binding checks. A 4 MiB
public-image cache supplies older Android versions independently of system fonts.
Variant selection emits the exact sequence. Cursor deletion combines bounded system
grapheme segmentation with the bundled RGI suffix trie; it reads at most 64 UTF-16
units only for the user's explicit Backspace action and retains no context history.

1. `ZeroInputService` 是唯一输入法服务并持有当前输入会话。每个会话都有单调递增的令牌，
   安全剪贴板在认证后返回编辑器，由用户再次确认时绑定当前令牌、实际 `InputConnection` 和
   交互序号；任一项变化都会取消待提交操作。认证导航期间仅保留短时的未消费授权，见下文。
2. `InputSessionController` 根据编辑器类型应用隐私策略，再将按键交给当前引擎。设置变化会在
   当前会话的下一次交互和输入视图恢复时重新求值；若权限收紧，会取消组合文本并重建引擎状态。
   `onStartInput` 先安装纯内存的轻量降级引擎，不在 IME 主线程创建 Rime 会话或扫描语言包。
   会话初始语言优先读取 `InputMethodManager` 提供的当前 ZeroInput 子类型（`zh-CN`/`en-US`），
   再回退到本地设置；这样即使 Android 调整 subtype 回调时序，系统选择也不会被旧设置覆盖。
   键盘上的中英文切换同时更新 Android 当前子类型，避免旋转或切换输入框时恢复旧语言。
  `EngineWarmupCoordinator` 在共享的有界单线程队列中创建并启动目标引擎，结果携带会话令牌、
  编辑器包名、语言包键和完整隐私快照；结果只有仍处于同一编辑器时才可转移所有权。无组合文本可
  直接交接；内置轻量拼音降级引擎的纯拼音组合可由准备好的全拼引擎恢复后受限接管，任何恢复失败、
  已选分词、语言包或不受支持的引擎均保留原组合。过期或拒绝的结果立即关闭。结果投递到 IME 所在线程前由独立的所有权交接器暂存，服务销毁或
  Handler 拒绝/移除回调时会回收尚未交接的引擎，避免后台 native 句柄成为孤儿。全拼引擎的
  related-reading secondary 会话也只在首次请求关联读音扩展时由同一有界执行器创建，并由
  会话代次和运行时锁保证过期任务不会留下 native 句柄。
3. 引擎返回不可变 `EngineUpdate`，控制器负责通过 `InputConnection` 提交或组合文本。
4. `PersonalizationStore` 是学习数据端口；加密实现位于 `user-data`，`ime-core` 不依赖具体仓库。
   应用层适配器负责在获得允许后按需后台预热和串行学习写入，输入主线程只读取内存缓存。
   会话或隐私策略变化以及清除个性化数据时都会提升队列代次，使此前排队但尚未执行的学习写入
   失效，并立即隐藏旧候选；用户主动清除还会删除用户词组和 emoji 历史各自的 Keystore 密钥。
5. emoji 最近记录与安全剪贴板无标签索引由后台线程读取并缓存。安全剪贴板正文只在一次性认证
   成功且用户返回确认后于专用后台线程解密，回到主线程前再次校验输入会话与交互序号；设置、语言包、
   子类型或个性化状态变化也会使尚未完成的认证请求失效。emoji 写入带有清除代次，用户清除
   数据后，已经排队但尚未执行的历史写入不会复活。
   引擎预热、个性化写入、索引刷新和认证读取均使用有限容量队列；取消任务后清理已排队的
   Future，清除个性化数据时会先取消旧的可选任务并以控制操作优先；仅设置页的耐久清除路径
   在极端拒绝情况下允许其后台工作线程作最后一次同步尝试，不让 IME 输入线程执行阻塞 I/O。
6. 语言包仅允许包含经过清单验证的数据文件，不允许动态代码或原生库。安装后通过独立注册表
   发现、启用和实例化数据型引擎；完整性扫描及词典加载不会在设置页主线程执行，首次后台
   扫描完成前，设置页只读取内存快照。当前只接受明确映射为 `zh` 或 `en` 的 BCP-47 标签，并在
   激活安装前确认包内至少有一条可用词典数据，避免“导入成功但无法输入”的死包。

## Panel navigation and composition handoff

Private clipboard management hands an owned `PendingClipboardAddition` buffer
from authentication to one background write. Cancellation revokes its operation
without waiting on storage; the owner clears pending or consumed buffers.
Repeated internal selection intents revoke the old request before installing a new review and
never transfer an authentication grant to replacement text.

The view owns one-level Back handling for tools, expanded candidates, expression
search, secondary panels and symbol pages. A lifecycle-bound predictive Back
registration exists only while a secondary UI level is active; legacy Back keys
use the same view operation through the IME service. The keyboard root retains
the platform dismissal behavior. UI-only navigation still invokes the existing
interaction invalidation boundary.

Horizontal gesture ownership remains with expression surfaces. Expanded candidates
use RecyclerView's native vertical dragging and fling. Two rows before an edge,
one posted page request extends the core window. Bounded list diffs append/remove
rows without full refresh or resetting the viewport; eviction preserves a visible
candidate and its pixel offset. Initial-page IDs stay stable when browsing begins.
Public engine pages and personal pages each retain at most six pages. Personal
pages append after native exhaustion, clear on revision/privacy changes, and keep
existing candidate identity/selection verification. Explicit page buttons remain
available for accessibility.

Secondary panels have a fixed expand/collapse action outside the scrolling toolbar.
Expanded content uses at least 65% of the current available window (or the larger
compact minimum); handwriting fullscreen fills the safe region. Current parent
measure constraints determine the budget, never the previous collapsed root height.
Expanded floating/one-hand panels temporarily use the available width without
changing saved placement. Search/AI editing budgets include both results and keys;
landscape places them side by side. Resizing a handwriting canvas clears its strokes.

The fuzzy master and keyboard shortcut select all pairs when enabled and clear
all pairs when disabled. Individual switches still select subsets and synchronize
the master state. The existing preference fields, deferred configuration and
engine/schema effective-mask contract remain unchanged.

Expression search labels are explicit internal edit targets. `app/input/LocalDraftInput`
owns an independent bounded conversion session, shared as an implementation with
AI drafts but instantiated separately. Its only commit target is a memory buffer;
it uses full pinyin/English and an empty personalization port. Search is limited
to 64 UTF-16 units. Keys, candidates, paging, language switching and composition
clear all route to that draft while editing. Panel/session/view/settings/subtype
and privacy changes close the search session and invalidate warm-up delivery.
No external editor connection or personal lookup is passed into either draft.

Rime's single-syllable candidate navigation uses a temporary internal caret;
ordinary typing/deletion resumes at the composition end. An acknowledged external
editor cursor change finishes the displayed preedit without rewriting or learning
it, clears engine/candidate state, and lets subsequent deletion use the editor's
new selection. This handoff reads no surrounding text and retains no old connection.

## Optional AI workbench

See [assessment and implementation plan](ai-workbench-plan.md) and
[ADR 0015](adr/0015-ai-workbench-boundary.md).

`ai-api` contains the platform-free request, action, policy, stream-event and conversation
contracts. `app` owns the Android composition root, `AiCoordinator` and the only network
adapter, `OpenAiCompatibleProvider`; `user-data` owns separately encrypted AI configuration
and optional conversation history. The provider accepts only HTTPS endpoints, disables redirects,
uses bounded timeouts and response sizes, and sends data only after the user submits text from
the keyboard AI panel or explicitly tests the selected model or fetches the model catalog in settings. The latter
uses `AiProviderProbe`, a fixed public prompt, no history/attachments, a 16-token
output cap and a 30-second timeout. It never reads editor context, selections, the system clipboard or the
private clipboard vault.

Provider configuration supports up to 16 profiles and 32 explicitly selected or manually named models
per profile, with image/audio support declared per model. `AiComposeActivity` owns
explicit text review and the system document picker; `AiAttachmentReader` bounds
reads on an application-owned document worker. `AiContentInbox` is a single expiring,
memory-only transfer. A fresh user tap in the eligible IME claims the content and
adds separate references and attachments without replacing the current question;
it never binds or retains an old editor connection. The review Activity is
nonexported; external sharing is not currently published in the manifest.
`AiWorkbenchController` copies selected attachments into each request. The provider
owns and wipes request copies exactly once, including queued rejection and transport
deadline cancellation; a running request releases them after serialization and
transport cleanup. The service retains only separate draft buffers and wipes those
on session, panel, settings and conversation transitions. Attachments never enter
saved history. Provider connect, write and read phases share one absolute deadline
measured from queue submission.

AI is disabled by default and requires both the user enable switch and the explicit network
switch. Sensitive/password/PIN/unknown, incognito and privacy-tightened sessions fail closed.
`SessionPrivacy.independentAiAllowed` is shared by the UI and request gate. Only
NO_SUGGESTIONS in ordinary text allows an independent AI draft without permitting
personal data; combined identifier, no-learning or user privacy restrictions still block AI.
The idle candidate header and the leading toolbar expose an AI icon without scrolling.
During composition the existing candidate capacity is preserved; Tools exposes AI.
In restricted editors it remains a dimmed explanation action, never a workbench
entry. A tap refreshes session privacy before opening; it does not enable AI or
networking, read editor content or submit a request.
Streaming results remain in the panel; only a separate user tap can commit a result to the
current `InputConnection`. AI text is composed in a separate memory-only draft session, so the
question never becomes external editor composition. Request generations, session tokens and an
independent AI data generation invalidate late callbacks and queued conversation writes when the
editor, settings, service or stored AI data changes. Conversation persistence is disabled by
default, exposes summaries before selection, and uses a dedicated Keystore alias under
`noBackupFilesDir`.

`LocalDraftInput` retains at most one prepared engine until its current composition
ends, resumes delivery after draft commands, and retries preparation on Rime readiness.
Submission resolves the highlighted conversion candidate and remaining segments in
the authoritative local buffer. Action selection does not submit; fixed primary
commands and a draft-language indicator distinguish editing from result browsing.

`AiConfigurationState` publishes loaded configuration and revokes it under the same
short lock. Its revision is independent of editor/data generations: an editor
privacy change cancels requests without discarding configuration initialization,
and a delayed read or save cannot restore an endpoint after networking is revoked.
Network cancellation disconnects on a bounded worker, avoiding TLS/IO locks on
the IME thread. Streaming deltas share at most one pending UI callback. Selection
callbacks are accepted only while the captured input connection is still the active
session connection and their previous anchor belongs to that session.

The provider settings dialog retains its bounded, view-owned draft while its
Activity is temporarily stopped for an app switch. Saving is explicit; cancellation,
page finish or destruction dismisses the dialog and clears the editable fields.
The draft is not copied into saved instance state or persistent storage and is
not restored after Activity recreation or process death.

librime 自身的用户词典被禁用。中文候选选择的拼音输入码和使用频率由 ZeroInput 的加密仓库
统一保存，从而让全局关闭学习、隐私模式和编辑器的无个性化请求作用于所有引擎。
`TYPE_TEXT_FLAG_NO_SUGGESTIONS` keeps public conversion candidates available in
known, non-sensitive text editors; it still disables personal reads, learning,
model ranking and next-word predictions. Password and unknown-editor checks run
first and continue to bypass engines entirely.
`SessionPrivacy.predictionsAllowed` is passed through `EditorContext` for both
immediate and worker-prepared engines. Built-in English and English language packs
preserve typed composition but do not offer completions when it is false. Chinese
conversion remains available. `IME_FLAG_NO_PERSONALIZED_LEARNING` alone still permits
public completions; prediction permission is part of preparation identity.

Debug builds expose a display-only editor diagnostic in `ime-ui`. It captures only the package
name, input type/class/variation, IME options and subtype, then combines them with the current
privacy, language, engine and Rime state. The line remains visible before composition, during
composition and when no candidates exist. It is cleared at session end and never logged,
persisted or sent off device; Release builds do not render it.

Rime readiness requires a worker-only public `nihao` conversion and candidate
commit probe, not just a nonzero native session handle. Each prepared primary
session is checked using its configured layout before ownership reaches the IME.
The probe has no editor connection or personalization store and resets its state.
Initialization or probe failure leaves the immediate fallback active and exposes
FAILED; pending initialization keeps PREPARING until a validated engine is adopted.
The keyboard displays a separate, high-contrast indeterminate progress strip
throughout PREPARING, including while tools or candidates are displayed. A first
Chinese key triggers one non-blocking reminder per editor/preparation attempt.
It does not buffer or replay keys. Ready/failed/hidden states cancel the reminder;
editor changes, window hiding and view disposal also cancel its Toast. The existing
failure state retains the retry control. No native progress percentage is invented.
内置全拼使用 `express_editor`：选中覆盖全部输入的候选后直接提交，分段选择继续保留剩余组合；
`Return` 显式绑定 `commit_composition`。随包资源版本变化会重新部署配置，不改变加密用户数据格式。

## Offline next-word suggestions

`NextWordPredictor` is an independent memory-only port in `engine-api`.
`engine-dictionary/WordAssociationIndex` loads bounded, project-authored public
phrase pairs on the existing engine worker. A volatile immutable provider is
published once; there are no asynchronous private-context tasks or delivery
callbacks. Queries before readiness return no candidates.

  The public corpus currently has 1,728 pairs and 735 distinct language/prefix
keys. A query still performs at most 32 suffix probes and returns at most eight
words. Multi-character Chinese suffixes use longest matching; one-character
Chinese keys require a full-context match to avoid completing an unrelated word
ending in that character. English retains word-boundary matching. Public data
and synthetic evaluation fixtures live in separate main/test resources.

`ime-core/WordAssociationSession` owns a 32-unit wipeable buffer of successful
IME commits and at most eight public continuations. Editor acceptance is reported
by `EditorConnection.commitText`; failed writes do not seed associations or
learning. Empty-composition predictions have `NEXT_WORD` routes, independent of
engine paging and personal-candidate overlays. Click identities are rechecked,
and Space/Enter never accept a prediction. English spacing and Chinese script
normalization happen before committing the selected public word.

The default-on setting remains subject to conservative editor and personalization
policies. Lifecycle, cursor, settings, direct insertion and engine boundaries
wipe the buffer and invalidate routes. No new module, network access, persistence
format or private-history query is introduced. See
[ADR 0013](adr/0013-offline-word-associations.md) for limits and alternatives.

## Private text collection

`app/clipboard/ClipboardImportActivity` is an exported, input-only adapter for
Android text processing and text sharing. `ClipboardImportIntent` validates
untrusted payloads and removes formatting; `ClipboardImportRequest` owns one
bounded mutable draft and its review/authentication/confirmation/save lifecycle.
`ClipboardImportView` presents only that draft, never existing vault metadata.
The existing AuthenticationBroker issues a one-use grant; a subsequent foreground
Save action schedules the addition on a bounded worker. No authentication callback
persists data by itself, and callers receive no result data.

The keyboard's explicit copy-selection control reads the active editor through
`ClipboardSelectionReader` on a bounded worker. Selection length, session,
connection and interaction/settings generation are rechecked before accepting
the result. `ClipboardSelectionTransfer` hands one frozen draft and its original
vault deletion generation to the nonexported `ClipboardSelectionImportActivity`
using a one-use token; text is not placed in the Intent. The transfer expires
after five seconds. The internal page reuses the import authentication/save
flow and owns the draft after handoff; it never rereads the source editor.

`SecurePasteCoordinator` owns a pending item ID, source editor metadata, deletion
generation and an unused one-use grant across system-authentication navigation.
It retains no IME, InputConnection or plaintext. Once the source editor returns,
`SecurePasteConsent` binds that new session for a visible Confirm paste action.
Only that action transfers the grant to a worker read bound to the actual current
connection and interaction sequence. The unused grant expires within 30 seconds;
settings/default-IME changes cancel it. Leaving or editing after return binding
also cancels. Programmatic panel resets and background engine adoption do not
count as new user confirmation, and never cause a paste. See ADR 0012.

AI history persistence is owned by the serial configuration storage worker. Saving
is explicit; writing a configuration with history disabled clears the encrypted
history store and its key before the disabled state is published. A failed purge
keeps the history repository fail-closed and the next explicit storage operation
retries it, so a stale saved conversation cannot remain silently usable.

The authentication Activity completes the broker handoff after its destruction,
so a successful prompt cannot bind a source editor while authentication navigation
is still exiting. Cancellation and timeouts remain valid during that handoff.
The broker retains the pending request until its owner-thread delivery is consumed.
Cancellation removes that request even when success is already queued; duplicate
completion cannot deliver a second result. Private management dialogs separately
protect their windows and discard editable drafts on dismissal.

`user-data/SecureClipboardVault` owns the encrypted format and serializes reads
and writes through the existing `EncryptedStore` port. Additions, metadata loads
and removals carry a deletion generation captured before queuing; management
Save captures it before authentication. Operations recheck under the lock and
after storage work. Clear advances the generation before waiting for the lock,
invalidates cached summaries and attempts both body and index deletion, including
their separate keys. Incomplete deletion blocks access in this vault instance
until an explicit clear retry succeeds. A failed index refresh hides cached rows
until authenticated reconciliation. No IME, engine or system clipboard dependency
is introduced. See [ADR 0006](adr/0006-private-text-import.md).

## Security storage primitives

`security/AesGcmEnvelope` owns the internal format 1 framing and standard JCE
AES-GCM operations; `AesGcmKeyStore` owns Android key access. Reads require an
existing key and never create a replacement for an unreadable file. Writes retain
the existing key creation policy, aliases, AAD and envelope layout.
`AuthenticationLifetime` is the internal, platform-independent one-use policy;
`AuthenticationGrant` still uses the monotonic Android clock and the existing
authentication broker. A caller may shorten, but cannot extend, its 30-second limit.

`EncryptedFileStore` distinguishes a missing file from an unreadable existing
path, checks AtomicFile commit/delete postconditions and wipes owned temporary
buffers. A failed file deletion still attempts dedicated key deletion. These
checks detect silent rename/delete failures; they do not prove power-loss
durability. Vault body and index remain independently atomic files. Repair and
deletion-pending flags are process-local, not a persisted transaction journal:
after restart a stale body-free index may need authenticated reconciliation, and
a failed clear must be retried. See [storage validation](security-storage-validation.md).

## Opt-in system clipboard guard

`app/clipboardguard` owns this platform-only feature, independently of the private
vault. `ClipboardGuardOptions` and `ClipboardGuardSession` define a body-free
policy with a minimal `SystemClipboardPort` (timestamp, clear, subscription).
`AndroidSystemClipboard` is the sole production adapter allowed by privacyCheck.
No guard dependency is added to user-data, security, engine-api or ime-core.

`ClipboardGuardRuntime` owns a bounded serial worker and immutable view state.
ZeroInputService attaches/detaches its lifecycle and observes state for the optional
fixed-height ime-ui reminder. Clipboard work and initial guard preference loading
never run on the input thread; settings are read as one atomic value snapshot.
Preferences, default-IME changes and service detachment invalidate the worker's
lease before queued destructive operations can run. Event and refresh wake-ups
are coalesced; clear submissions are bounded to one. Automatic-mode startup also
processes existing content; other modes only record a baseline. A foreground inspection command can
create a confirmation ticket for an existing item, even if no callback arrived.
That ticket permits explicit cleanup in the otherwise observation-only mode;
inspection itself never triggers automatic clearing. A separate automatic request
from the focused settings page applies the already selected automatic policy to
an existing item. Foreground operations use a revocable focus lease, independent
of default-IME identity; background callbacks still require a selected, attached
IME. Runtime tests inject a synthetic
metadata port and default-IME predicate without accessing the platform clipboard.

The nonexported settings and clear Activities expose separate reminder, cleanup
and authentication choices. Notifications request permission only on user opt-in,
contain generic status, and open a page through an immutable PendingIntent.
Authentication uses the existing one-use grant with a distinct allowlisted title;
successful authentication still requires foreground confirmation. This adds no
encrypted format, persisted ticket, secret cache, polling or background service.
See [ADR 0007](adr/0007-system-clipboard-guard.md) for public-API limitations.

`ClipboardGuardOverlay` owns one application overlay on the main thread, separate
from the metadata worker. It holds generic status and opaque event identity only.
Settings own permission education and opt-in; granting SYSTEM_ALERT_WINDOW does
not enable clipboard access or any background service. Activity lifecycle,
screen-off and permission revocation callbacks remove the window. Overlay taps
open the existing nonexported confirmation flow. Position and bounded duration
are independent preferences. See [ADR 0008](adr/0008-clipboard-overlay.md).

## Expressions

`ime-ui` owns the immutable public emoji/kaomoji catalog, bounded keyword search,
kaomoji subtags and presentation models. It performs no file I/O. The new
`app/expressions` management surface maps user-data models to UI values;
`ZeroInputService` gates personal reads and selections with the current editor,
privacy generation and library revision. UI-only interactions reevaluate privacy.
Retired adapter bindings clear text and cannot finish an earlier touch gesture.

`PersonalExpressionRepository` serializes favorites/custom edits through the
existing encrypted store port, with a separate Keystore alias and a bounded
versioned binary format. There is no repository plaintext cache. Only the visible
management page or active permitted IME holds personal display snapshots.
`EmojiHistoryRepository` retains JSON format 1 and its dedicated key, extends
valid text length to 128 UTF-16 units, and checks strict decoding, cancellation
and deletion generation. Failed writes do not publish unsaved history. See
[ADR 0009](adr/0009-personal-expressions.md). User initiated expression exports
use a separate Keystore alias from the repository file and are device-bound;
the management UI never writes plaintext export files.

The IME window uses an explicit transparent, nondimming base theme in all resource
variants. Android fullscreen extraction stays disabled. In landscape, the
composition/status area and candidates share a 48dp row, keyboard rows use at least
48dp height and the expression panel uses 224dp. This leaves the host editor
visible without stretching the input view to the application window.

## User lexicon persistence and transfer

`UserLexiconRepository` depends on the small `security/EncryptedStore` port;
production still uses `EncryptedFileStore`, Android Keystore and the existing
AES-GCM envelope. The repository serializes background reads and writes under
one lock. It builds a separate update, persists it, then publishes its immutable
memory snapshot. Failed writes leave the previous snapshot intact. Clearing
advances an atomic generation before waiting for the lock, rejects older waiting
updates, and removes the ciphertext and dedicated key after any in-flight write.
If deletion fails, the repository remains unavailable until deletion is retried.
The IME continues to access only `QueuedPersonalizationStore` memory snapshots.

`UserLexiconFormat` uses Android's strict streaming `JsonReader` and accepts only
the existing format-1 root object, terms array and flat term objects. It rejects
unknown or duplicate fields, nested values, duplicate identities/IDs, invalid
UTF-8, invalid scalar types, unsupported languages and oversized data before
mutation. Imports merge by language, case-insensitive input code and phrase;
existing local IDs remain stable and new entries receive fresh local IDs.
Manual additions and imports fail atomically beyond 20,000 terms or 5 MiB of
compact JSON. Learning retains its existing bounded frequency/recency eviction.
  Exports serialize the same compact JSON representation, then encrypt it with
  a dedicated Android Keystore alias for user initiated transfers. The export
  is device-bound: clearing app data or deleting that export key makes existing
  files unrecoverable. The internal file format and production storage key alias
  remain unchanged; import still applies the existing size and schema limits.

  Transfer confirmations belong to the settings presentation layer. They explain
  merge/overwrite behavior and device-bound export scope before opening the
system document picker. Parser exceptions are converted to content-free failure
codes; the UI uses localized messages and never displays raw exceptions. See
[ADR 0005](adr/0005-user-lexicon-boundary.md).

## Chinese input configuration

`ChineseInputOptions` is an immutable engine-api value (script, abbreviations,
thirteen independent fuzzy pairs, punctuation, bounded page size, and keyboard layout). Optional
`ConfigurableChineseEngineFactory` and explicit `EngineCapability` declarations
allow another Chinese engine to implement the same product settings without
exposing Rime options to the controller. `CandidateTextNormalizer` applies the
selected script to personal candidates before deduplication and routing; the
encrypted original and its learning identity are preserved.

The service captures options in every warm-up request and checks them again at
handoff. Pending authenticated actions are cancelled before a prepared engine
replaces the current engine. Ordinary setting changes wait until composition is idle. Before a
different syllable index is deployed, the old native session is retired to the
memory fallback. Deployment refuses to run while a native session remains alive;
release of a superseded session makes preparation retryable. Configuration,
deployment and OpenCC initialization stay on the existing bounded engine worker.
Only the current generated configuration and prism are retained. These files
contain settings and public dictionary indexes, never personal input.

The bundled schema uses JSON syntax, a YAML subset understood by librime, so
Android's structured JSON parser can derive bounded configurations. Rime compiles
fuzzy and abbreviation rules into its own prism; the app does not implement a
second pinyin parser. OpenCC text dictionaries come from the pinned source archive
and are hash-checked during the build. No runtime downloads are involved.

The input view shares one stable header between the toolbar and candidate strip:
72dp in portrait (24dp composition/status plus 48dp candidates), 48dp in landscape.
An explicit tools button opens the toolbar while composing without moving keys.
Compact candidate browsing reuses the keyboard's measured row geometry, including
density rounding; the separate panel expansion action increases the viewport. Candidate views are reused, reject clicks whose binding changed during
the gesture, and clear their text when retired. Held backspace clears a composition
once, or repeats editor deletion until release/cancellation; panel, session and
privacy transitions cancel scheduled gesture callbacks.

Each Rime update is copied through one JNI context read after processing the key,
instead of reading the same native context separately for each field. Candidate
paging does not rewrite unchanged Android composing spans, and commitText already
clears the previous span. The engine API and synchronous ownership of editor
mutations remain unchanged.

## Engine compatibility

The optional `model-scoring` module depends only on `engine-api` and the pinned
offline ONNX Runtime. `app` composes it through `CandidateScorer`; Rime/JNI never
owns the model. `ime-core/ModelRankingPolicy` and controller revisions preserve
candidate identity and routing. The app coordinator owns successful-commit context
and UI/privacy lifecycle; `AsyncCandidateRanker` owns one lazy worker, one pending
request and a coalesced content-free delivery. Disabled input creates no model
or worker. See [ADR 0011](adr/0011-experimental-model-ranking.md) and
[model integration](model-integration.md).

Rime is the sole built-in Chinese engine, with its existing controlled fallback.
The test dictionary engine and engine-choice preference have been removed.
`engine-dictionary` retains only public next-word indexes and glide decoding.
Language packs and encrypted personalization remain separate capabilities.

Chinese nine-key input is an optional Rime capability. Rime's algebra compiles
telephone digits into its public spelling index on the preparation worker;
segmentation, ranking, abbreviations and fuzzy matching remain in librime.
`EngineSnapshot.readings` and the optional `ReadingSelectionEngine` port expose
bounded reading choices without leaking native types. The syllable table is
generated from the pinned Luna Pinyin data at build time. Choosing a reading
replaces the first unresolved numeric span through an allowlisted JNI call;
backspace can undo that choice. Partial candidate selection disables reading
replacement so fixed text cannot be overwritten. Reset and close clear history.
Text editors in English, sensitive editors and engines without the capability keep the full
keyboard. Number, phone and date/time editors use a literal numeric layout.
Literal digits from the symbols page finish composition before direct
commit, so they cannot accidentally become nine-key spelling codes.

Keyboard rows allocate cumulative pixel boundaries across the full available
width. Rectangular keys dispatch on release in the current event, support
independent pointers and cancel when a gesture leaves the target. Input remains
synchronous through ime-core; public-fixture instrumentation measures key
dispatch, editor updates and the next frame separately.

## Keyboard appearance and editor actions

`ime-core/EditorInputOptions` contains only immutable public editor configuration:
layout class, numeric flags and Enter action. It is independent of input text and
privacy decisions. The controller and UI use the same Enter policy, including
`IME_FLAG_NO_ENTER_ACTION`. Existing conservative editor privacy classification
continues to decide whether suggestions and personalization are allowed.
When a Chinese or English composition is active, Enter commits its raw input
without performing the editor action. Only Enter with no active composition
sends a newline or performs the editor action. Space and explicit candidate
selection retain candidate conversion. The editor adapter registers expected
selection and composing-span updates before platform calls, so synchronous
callbacks and delayed acknowledgements cannot discard a subsequent key.

`ime-ui/KeyboardAppearance` owns independent palette, material, geometry and background
values. Six materials share unchanged rectangular touch geometry. Dedicated key
and background drawables do bounded rendering only; no file reads, bitmap decoding,
EXIF parsing or blur occurs in the UI. Background opacity blends onto the opaque
palette surface, leaving text opacity and the IME touch region unchanged.
`app/settings/AppearancePreferences` persists nonsensitive choices and an image UUID,
preserving existing palette/height keys with defaults for the additional fields.
`KeyboardBackgroundStore` owns a bounded serial worker, the system-granted image
import, and a dedicated AES-GCM file/key under noBackupFilesDir. It normalizes and
re-encodes bounded PNG/JPEG/WebP rasters, strips metadata and atomically writes an
independent revision before publishing its identifier. No URI permission is retained.
Deletion revokes pending imports, clears the selected identifier, then removes files
and the isolated key on the serial worker. Orphans are cleaned on startup/mutation.
A separate bounded cancellation worker closes stalled provider descriptors; requests
expire after 15 seconds. View-scoped `KeyboardAppearanceBinding` owns display bitmaps,
cancels stale delivery and releases images on view replacement/hide/destruction.
There is no application-wide decoded-image cache. Appearance changes keep the existing
session/authentication invalidation. The nonexported secure appearance Activity previews
the real keyboard without editor, engine or personal-data callbacks. No theme downloads,
new permissions or exported components are introduced.

Keyboard rows and keys are reused while their weight geometry is unchanged.
One-shot Shift resets after a letter; a timed hold locks case. Binding revisions,
gesture cancellation and view release prevent old touches from sending a changed
key. Insets affect decoration only, keeping continuous rectangular touch regions.

`InputEngine` 是稳定端口。native 适配器还包含单独的 `NativeRimeBridge` 边界，因此 librime C API
变化不会传播到 Kotlin 业务层。Rime 运行时显式发布未初始化、初始化中、就绪和失败状态；native
库缺失、初始化失败或无法创建探针会话都会进入失败状态并使用降级引擎。发行构建通过
`requireRime` 属性阻止误用降级引擎。

## AI provider routing and discovery (2026-10-07)

`ai-api/AiEndpoint` validates and resolves API bases and full Chat Completions
addresses. `AiModelCatalog` is separate from the generation port. Its sole runtime
implementation is still `app/ai/OpenAiCompatibleProvider`; `AiTransportExchange`
is an injectable I/O contract whose only production implementation lives inside
that provider. `AiResponseReader` bounds catalog and non-streaming JSON responses.
No network dependency or permission is added.

`AiModelDiscovery` owns a cancellable settings operation; `AiModelPicker` owns its
protected, unsaved multi-selection. An explicit fetch uses the current edit's key
and endpoint with the saved network policy. Saving continues to use the same
configuration repository and format. Manual models and per-model capabilities
are preserved without treating catalog metadata as capability grants.

Request configuration binds at enqueue time; execution rejects a changed snapshot.
The absolute timer includes queue wait. Cancellation purges only cancelled tasks,
not other operations. Workbench configuration reconciliation revokes old context,
results and pending writes even before the settings observer is delivered.

The workbench header exposes the current model and New chat while editing or
viewing results. Quick selection uses only the active provider's saved model IDs;
`AiWorkbenchController` owns this transient choice. `AiRequest.model` binds it to
the request, and the transport validates membership and media capabilities against
that model in the captured configuration. Switching starts empty context, revokes
pending output/persistence, and clears draft attachments. Closing the workbench or
changing configuration restores the saved default. New chat retains the transient
model but discards the draft/context. Neither action sends a request or changes
the stored configuration/conversation formats.

SSE data lines are assembled per event within fixed limits. Completion requires a
single text choice ending with `stop`, followed by `[DONE]`. Missing stops,
post-stop content, tool payloads, multiple choices and trailing JSON are rejected;
usage-only events remain supported.


## Explicit AI page references (2026-10-07)

ADR 0019 adds a separate optional platform adapter under `app/ai/page`.
`PageReferenceService` owns Android accessibility nodes; `PageTextCollector`
performs bounded traversal through a testable node port. `PageReferenceBroker`
connects that service to `AiPageReferenceBinding`, whose lifetime belongs to the
IME. The broker retains no text or InputConnection. It uses its own one-thread,
one-queued-task worker, independent of key conversion, transport and storage.
Event callbacks only invalidate via window metadata; only an explicit capture
request retrieves nodes. Generation checks occur during traversal, UI delivery,
reference acceptance, Send and Insert. See ADR 0019 for limits and OS caveats.
Window-state and window-set events conservatively revoke review without synchronous
window enumeration, including navigation that reuses source identifiers. Unselected
snapshots expire after 30 seconds; checklist views retain only 160-character previews,
and the binding alone owns the complete snapshot until confirmation or expiry.

`ai-api/AiReference` defines platform-free source content and the combined context
budget. `AiContextSelection` owns selected message indices and transient references,
independent of the saved transcript. The provider encodes references as quoted
USER data and never promotes source text to SYSTEM instructions. The context view
can preview exactly selected history and references; unchecked blocks do not enter
requests. New chat/model/editor transitions reset selection and revoke page work.
Explicit Cancel also clears selected history/references and revokes page work;
ordinary draft editing stops output while preserving the user's context choices.

`AiConversationListView`, `AiContextView` and `AiPageSelectionView` split presentation
by responsibility within a bounded keyboard viewport. `AiConversationNameEditor`
uses the isolated draft for a title, then restores the question. Encrypted rename
uses the existing title field and serial storage worker; there is no format change.
Internal imported text becomes removable references. `AiComposeActivity` remains
nonexported: earlier external sharing descriptions are superseded by this entry.


## Review hardening (2026-10-08, round 2)

Settings exposes explicit whole-vault deletion independently of feature enablement
and personalization clearing. `SecureClipboardVault.purge` reads no plaintext;
it shares the authenticated clear path's generation, lock, two-store/key deletion
and fail-closed retry behavior. The UI cancels pending paste consent before
scheduling storage work. See ADR 0002 for the approved deletion semantics.
Management additions have a 30-second monotonic deadline and an owner-thread
expiry callback; late workers recheck the deadline and clear owned buffers.

Language selection writes the built-in language and cleared package in one
preference transaction, then refreshes the IME mirrors before another key.
Viewport policy supplies the wide-window row-height choice separately from the
compact-row constraint. Unused public Rime configuration deletion is best effort;
failures are retried on a later configuration switch and do not disable a valid
selected schema. Persistent filesystem failure may retain unused public files.

Language-pack dictionary loading verifies bounded, strictly decoded UTF-8 and the
manifest hash of every parsed file, including files after the entry budget fills.
Any missing, truncated, modified or unreadable payload rejects the entire load.
ZIP central-directory metadata is checked before extraction: special Unix types,
including symlinks, are rejected. This metadata-only bounded parser supplements
`ZipFile`, canonical paths, staging isolation and manifest/hash validation; it
never extracts files. Multi-disk, ZIP64 and inconsistent central-directory layouts
are rejected. The installer uses the same fail-closed enablement fallback as scans
when saved enablement state is corrupt.

AI storage errors have a distinct user-visible classification; failed mutations
do not claim history loading failed. All stream failures show the result pane.
Page-reference binding accepts the broker and enablement query directly, preserving
its owner-thread lifecycle while allowing isolated delayed-delivery regressions.
Current-but-invalid delivery releases pending review immediately; superseded
callbacks cannot clear a newer request. Confirmed reference rows default to bounded
Unicode-safe previews. Full context remains available by explicit preview, and
leaving that pane resets the full-text display. This reduces View text copies;
the controller's bounded immutable strings still remain until context invalidation.
