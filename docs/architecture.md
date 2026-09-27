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
 +-> language-pack -----> engine-api

user-data -> security
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
  编辑器包名、语言包键和完整隐私快照；只有仍处于同一编辑器且没有组合文本时才转移所有权，
  过期或拒绝的结果立即关闭。结果投递到 IME 所在线程前由独立的所有权交接器暂存，服务销毁或
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

The public corpus currently has 1,698 pairs and 723 distinct language/prefix
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

The authentication Activity completes the broker handoff after its destruction,
so a successful prompt cannot bind a source editor while authentication navigation
is still exiting. Cancellation and timeouts remain valid during that handoff.

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
[ADR 0009](adr/0009-personal-expressions.md).

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
Exports use the same compact JSON representation so new writes fit the import
limit. The format version, encrypted file name and key alias are unchanged.

Transfer confirmations belong to the settings presentation layer. They explain
merge/overwrite behavior, export scope and plaintext exposure before opening the
system document picker. Parser exceptions are converted to content-free failure
codes; the UI uses localized messages and never displays raw exceptions. See
[ADR 0005](adr/0005-user-lexicon-boundary.md).

## Chinese input configuration

`ChineseInputOptions` is an immutable engine-api value (script, abbreviations,
eight independent fuzzy pairs, punctuation, bounded page size, and keyboard layout). Optional
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
Expanded candidates reuse the keyboard's measured row geometry, including density
rounding. Candidate views are reused, reject clicks whose binding changed during
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

Rime remains the default Chinese engine. `engine-dictionary` is a separate JVM
module with a small bundled Apache-2.0 reference dictionary and bounded prefix
lookup. It implements the same engine lifecycle and advertises only punctuation
and page-size support. The selected factory and full options snapshot travel in
every warm-up request; unsupported controls are disabled and the effective
keyboard stays full. No engine owns personalized storage.

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

`ime-ui/KeyboardAppearance` owns four resource overlays and three row heights.
`app` persists only their enum names as nonsensitive settings and applies the
overlay over the IME window theme. Appearance changes replace the view, release
its old callbacks and personal bindings, then render the current controller
snapshot. The existing settings invalidation also cancels pending authentication.
The nonexported appearance Activity previews the real keyboard with no editor,
engine or personal-data callbacks. There is no theme download or external asset.

Keyboard rows and keys are reused while their weight geometry is unchanged.
One-shot Shift resets after a letter; a timed hold locks case. Binding revisions,
gesture cancellation and view release prevent old touches from sending a changed
key. Insets affect decoration only, keeping continuous rectangular touch regions.

`InputEngine` 是稳定端口。native 适配器还包含单独的 `NativeRimeBridge` 边界，因此 librime C API
变化不会传播到 Kotlin 业务层。Rime 运行时显式发布未初始化、初始化中、就绪和失败状态；native
库缺失、初始化失败或无法创建探针会话都会进入失败状态并使用降级引擎。发行构建通过
`requireRime` 属性阻止误用降级引擎。
