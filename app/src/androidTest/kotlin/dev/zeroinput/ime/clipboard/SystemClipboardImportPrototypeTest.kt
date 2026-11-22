package dev.zeroinput.ime.clipboard

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.ZeroInputService
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.StegoAction
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P1 only: never packaged in the IME; no vault, authentication, cleanup or listener wiring. */
@RunWith(AndroidJUnit4::class)
class SystemClipboardImportPrototypeTest {
    @Test fun disabledStegoSystemPasteRejectsEveryTargetBeforeAuthorization() {
        val settings = (context.applicationContext as ZeroInputApplication).graph.settings
        val defaults = settings.stegoDefaults
        try {
            instrumentation.runOnMainSync { settings.stegoDefaults = defaults.copy(systemPaste = false) }
            val adapter = dev.zeroinput.ime.textstego.StegoSystemPaste(context)
            for (field in dev.zeroinput.ime.ui.StegoField.entries) {
                assertNull(adapter.read(field) { fail("Disabled paste must not reach authorization"); true })
            }
        } finally { instrumentation.runOnMainSync { settings.stegoDefaults = defaults } }
    }

    @Test fun longPressSystemPastePopulatesOnlyTheIsolatedCoverThroughTheSourceWorker() = withCleanDevice { activity, manager, ownership ->
        val sample = Sample.GUARD
        manager.setPrimaryClip(publicFixture(sample))
        ownership.record(manager.primaryClipDescription?.timestamp)
        val graph = (context.applicationContext as ZeroInputApplication).graph
        val defaults = graph.settings.stegoDefaults
        lateinit var view: dev.zeroinput.ime.ui.ZeroInputView
        lateinit var workspace: dev.zeroinput.ime.textstego.StegoWorkspace
        try {
            instrumentation.runOnMainSync {
                graph.settings.stegoDefaults = defaults.copy(systemPaste = true)
                val themed = dev.zeroinput.ime.settings.KeyboardThemeContext.create(activity, dev.zeroinput.ime.ui.KeyboardTheme.CLASSIC)
                view = dev.zeroinput.ime.ui.ZeroInputView(themed)
                activity.setContentView(view)
                workspace = dev.zeroinput.ime.textstego.StegoWorkspace({ graph }, android.os.Handler(android.os.Looper.getMainLooper()),
                    view, { !activity.isFinishing && !locked() }, { null }, {}, {}, {}, isKeyboard = false)
                view.openTextStego()
                workspace.open(dev.zeroinput.engine.api.InputLanguage.ENGLISH, dev.zeroinput.engine.api.ChineseInputOptions())
                view.textStegoPanel.onPasteRequested(dev.zeroinput.ime.ui.StegoField.COVER)
            }
            val label = context.getString(dev.zeroinput.ime.R.string.stego_paste_system)
            ClipboardDeviceTestSupport.await { ClipboardDeviceTestSupport.findText(label) != null }
            val bounds = android.graphics.Rect()
            requireNotNull(ClipboardDeviceTestSupport.findText(label)).getBoundsInScreen(bounds)
            ClipboardDeviceTestSupport.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            await {
                var found = false
                instrumentation.runOnMainSync {
                    fun contains(owner: android.view.View): Boolean = owner is android.widget.TextView && owner.text.toString() == Sample.GUARD.expected ||
                        owner is android.view.ViewGroup && (0 until owner.childCount).any { contains(owner.getChildAt(it)) }
                    found = contains(view.textStegoPanel)
                }
                found
            }
        } finally {
            instrumentation.runOnMainSync { workspace.close(); view.release(); graph.settings.stegoDefaults = defaults }
            await { activity.hasWindowFocus() && !locked() }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun imeCanPasteWordingBeforeChoosingStyleAndOpenWithTheIndicatedRandomKey() =
        withCleanDevice(ordinaryEditor = true) { activity, manager, ownership ->
            val password = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
            val body = "公开平台回归样本\r\n保留原文本。"
            val sample = Sample.WORDING_WINDOW
            val encoded = publicFixture(sample).getItemAt(0).text.toString()
            val graph = (context.applicationContext as ZeroInputApplication).graph
            val defaults = graph.settings.stegoDefaults
            val driver = ClipboardDeviceTestSupport
            val original = driver.shell("settings get secure default_input_method").trim()
            val own = ComponentName(context, ZeroInputService::class.java).flattenToString()
            val wasEnabled = driver.shell("ime list -s").lineSequence().any { it.trim() == own }
            val incognito = graph.settings.incognitoMode
            try {
                driver.onMain {
                    graph.settings.incognitoMode = false
                    graph.settings.stegoDefaults = defaults.copy(systemPaste = true)
                }
                driver.shell("ime enable $own")
                driver.shell("ime set $own")
                driver.showFixtureKeyboard(activity as InputFixtureActivity)
                instrumentation.uiAutomation.waitForIdle(300, 3000)
                assertTrue(ownership.mayReplace(manager.primaryClipDescription?.timestamp))
                manager.setPrimaryClip(publicFixture(sample))
                ownership.record(manager.primaryClipDescription?.timestamp)
                lateinit var keyboard: ZeroInputView
                driver.onMain {
                    keyboard = android.view.inspector.WindowInspector.getGlobalWindowViews().asSequence()
                        .flatMap { fixtureViews(it).asSequence() }.filterIsInstance<ZeroInputView>().first { it.isShown }
                    keyboard.onTextStegoRequested()
                    assertTrue(keyboard.isTextStegoOpen)
                    keyboard.textStegoPanel.onAction(StegoAction.DECRYPT)
                }
                touchStegoControl(keyboard, dev.zeroinput.ime.ui.R.string.stego_carrier_hint, hold = true)
                assertTrue("The current item must still be the public fixture",
                    ownership.mayReplace(manager.primaryClipDescription?.timestamp))
                tapStegoMenu(dev.zeroinput.ime.R.string.stego_paste_system, activity)
                awaitPanelText(keyboard, encoded, "Wording must remain in the private draft before a style is chosen")

                touchStegoControl(keyboard, dev.zeroinput.ime.ui.R.string.stego_decrypt_start)
                tapStegoMenu(dev.zeroinput.ime.R.string.stego_wording_category, activity)
                tapStegoMenu(dev.zeroinput.ime.R.string.stego_wording_window, activity)
                awaitPanelText(keyboard, context.getString(dev.zeroinput.ime.R.string.stego_wording_receive_key),
                    "Inspect the carrier before the first decryption attempt")
                driver.onMain {
                    keyboard.dispatchKeyboardAction(KeyboardAction.LiteralText(password))
                }
                touchStegoControl(keyboard, dev.zeroinput.ime.ui.R.string.stego_decrypt_start)
                awaitPanelText(keyboard, context.getString(dev.zeroinput.ime.R.string.stego_result),
                    "The first submitted credential should open the carrier")
                driver.onMain {
                    assertFalse(fixtureViews(keyboard.textStegoPanel).any {
                        it is android.widget.TextView && it.isShown && it.text.toString() == body
                    })
                    keyboard.textStegoPanel.onAction(StegoAction.REVEAL)
                    assertTrue(fixtureViews(keyboard.textStegoPanel).any { it is android.widget.TextView && it.text.toString() == body })
                    assertTrue(activity.editor.text.isEmpty())
                }
            } finally {
                driver.onMain {
                    graph.settings.stegoDefaults = defaults
                    graph.settings.incognitoMode = incognito
                }
                if (original.isNotBlank() && original != "null") {
                    driver.shell("ime set $original")
                    if (!wasEnabled && original != own) driver.shell("ime disable $own")
                }
            }
        }

    private fun touchStegoControl(keyboard: ZeroInputView, labelId: Int, hold: Boolean = false) {
        val driver = ClipboardDeviceTestSupport
        val point = IntArray(2)
        lateinit var field: android.widget.TextView
        driver.onMain {
            val controls = fixtureViews(keyboard.textStegoPanel).filterIsInstance<android.widget.TextView>()
            field = checkNotNull(controls.singleOrNull {
                it.isShown && (if (hold) it.hint else it.text)?.toString() == it.resources.getString(labelId)
            }) {
                val known = listOf(dev.zeroinput.ime.ui.R.string.stego_decrypt, dev.zeroinput.ime.ui.R.string.stego_decrypt_start,
                    dev.zeroinput.ime.ui.R.string.stego_working, dev.zeroinput.ime.R.string.stego_choose_received_style)
                val visible = known.filter { id -> controls.any { it.isShown && it.text.toString() == it.resources.getString(id) } }
                "Missing ${keyboard.resources.getResourceEntryName(labelId)} in open=${keyboard.isTextStegoOpen}, " +
                    "visible controls: ${visible.map(keyboard.resources::getResourceEntryName)}"
            }
            field.requestRectangleOnScreen(android.graphics.Rect(0, 0, field.width, field.height), true)
        }
        instrumentation.uiAutomation.waitForIdle(300, 3000)
        driver.onMain {
            val bounds = android.graphics.Rect()
            assertTrue(field.getGlobalVisibleRect(bounds))
            field.rootView.getLocationOnScreen(point)
            point[0] += bounds.centerX()
            point[1] += bounds.centerY()
        }
        val coordinates = "${point[0]} ${point[1]}"
        driver.shell(if (hold) "input swipe $coordinates $coordinates 750" else "input tap $coordinates")
    }

    private fun tapStegoMenu(label: Int, editorActivity: Activity) {
        val driver = ClipboardDeviceTestSupport
        val text = context.getString(label)
        driver.await("Menu item should be shown") { driver.findText(text) != null }
        val bounds = android.graphics.Rect()
        requireNotNull(driver.findText(text)).getBoundsInScreen(bounds)
        assertFalse(bounds.isEmpty)
        driver.onMain { assertTrue("A Stego picker must keep the editor focused", editorActivity.hasWindowFocus()) }
        driver.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    private fun awaitPanelText(keyboard: ZeroInputView, expected: String, diagnostic: String) {
        try {
            ClipboardDeviceTestSupport.await(diagnostic) {
                var present = false
                ClipboardDeviceTestSupport.onMain {
                    present = fixtureViews(keyboard.textStegoPanel).any {
                        it is android.widget.TextView && it.isShown && it.text.toString() == expected
                    }
                }
                present
            }
        } catch (_: AssertionError) {
            ClipboardDeviceTestSupport.onMain {
                val labels = listOf(dev.zeroinput.ime.R.string.stego_system_paste_no_text, dev.zeroinput.ime.R.string.stego_carrier_invalid,
                    dev.zeroinput.ime.R.string.stego_choose_received_style, dev.zeroinput.ime.R.string.stego_wording_style_mismatch,
                    dev.zeroinput.ime.R.string.stego_wording_receive_key, dev.zeroinput.ime.R.string.stego_wording_open_failed)
                val visible = labels.filter { id -> fixtureViews(keyboard.textStegoPanel).any {
                    it is android.widget.TextView && it.isShown && it.text.toString() == it.resources.getString(id)
                } }
                fail("$diagnostic. open=${keyboard.isTextStegoOpen}, notices=${visible.map(keyboard.resources::getResourceEntryName)}")
            }
        }
    }

    private fun fixtureViews(view: android.view.View): List<android.view.View> = listOf(view) +
        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { fixtureViews(view.getChildAt(it)) } else emptyList()

    @Test fun stegoPasteReadsOnlyExplicitTextAndRejectsWrongTargetsBoundsAndRevocation() = withCleanDevice { _, manager, ownership ->
        val adapter = dev.zeroinput.ime.textstego.StegoSystemPaste(context)
        val settings = (context.applicationContext as ZeroInputApplication).graph.settings
        val defaults = settings.stegoDefaults
        try {
        instrumentation.runOnMainSync { settings.stegoDefaults = defaults.copy(systemPaste = true) }
        for (sample in prototypeSamples) {
            assumeTrue(ownership.mayReplace(manager.primaryClipDescription?.timestamp))
            manager.setPrimaryClip(publicFixture(sample))
            ownership.record(manager.primaryClipDescription?.timestamp)
            val allowed = sample in listOf(Sample.UNICODE, Sample.GUARD, Sample.HTML, Sample.BLANK)
            val carrier = adapter.read(dev.zeroinput.ime.ui.StegoField.CARRIER) { true }
            assertTrue("Public fixture classification differs: ${sample.name}", allowed == (carrier != null))
            if (carrier != null && sample.expected != null) assertTrue(carrier == sample.expected)
            val cover = adapter.read(dev.zeroinput.ime.ui.StegoField.COVER) { true }
            assertTrue("Public cover bound differs: ${sample.name}", (allowed && sample != Sample.LIMIT) == (cover != null))
            var checks = 0
            assertNull(adapter.read(dev.zeroinput.ime.ui.StegoField.CARRIER) { ++checks == 1 })
            assertNull(adapter.read(dev.zeroinput.ime.ui.StegoField.COVER) { false })
            val secret = adapter.read(dev.zeroinput.ime.ui.StegoField.SECRET) { true }
            assertEquals(allowed && sample != Sample.LIMIT, secret != null)
            assertNull(adapter.read(dev.zeroinput.ime.ui.StegoField.CREDENTIAL) { fail("Wrong target must not reach authorization"); true })
        }
        instrumentation.runOnMainSync { settings.stegoDefaults = defaults.copy(systemPaste = false) }
        for (field in listOf(dev.zeroinput.ime.ui.StegoField.COVER, dev.zeroinput.ime.ui.StegoField.SECRET,
                dev.zeroinput.ime.ui.StegoField.CARRIER)) {
            assertNull(adapter.read(field) { fail("Disabled system paste must not acquire an item"); true })
        }
        } finally { instrumentation.runOnMainSync { settings.stegoDefaults = defaults } }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun inactiveRevokedExpiredAndRepeatedActionsDoNotRead() {
        var reads = 0
        var now = 0L
        val probe = Probe({ reads++; publicFixture(Sample.UNICODE) }, { now })
        assertNull(probe.read(false, true))
        assertNull(probe.read(true, false))
        assertEquals(0, reads)
        probe.arm()
        now = 5_000L
        assertNull(probe.read(true, true))
        assertEquals(0, reads)
        probe.arm()
        probe.cancel()
        assertNull(probe.read(true, true))
        assertEquals(0, reads)
        probe.arm()
        probe.read(true, true)?.close()
        assertNull(probe.read(true, true))
        assertEquals(1, reads)
    }

    @Test
    fun nullAndExplicitDenialAreUnavailableAndNeverProduceADraft() {
        for (source in listOf<() -> ClipData?>({ null }, { throw SecurityException("fixed test denial") })) {
            val probe = Probe(source)
            probe.arm()
            assertNull(probe.read(true, true))
            assertNull(probe.read(true, true))
        }
    }

    @Test
    fun revocationWhileReadingDiscardsTheResult() {
        lateinit var probe: Probe
        probe = Probe({ probe.cancel(); publicFixture(Sample.UNICODE) })
        probe.arm()
        assertNull(probe.read(true, true))
    }

    @Test
    fun eligibilityLostWhileReadingDiscardsTheResult() {
        var eligible = true
        val probe = Probe({ eligible = false; publicFixture(Sample.UNICODE) }, eligibility = { eligible })
        probe.arm()
        val draft = probe.read(true, true)
        try { assertNull("Revoked access must discard the acquired snapshot", draft) } finally { draft?.close() }
    }

    @Test
    fun preflightWithoutAcquisitionDoesNotAuthorizeCleanup() {
        var clears = 0
        withCleanDevice(onClear = { clears++ }) { _, _, _ -> }
        assertEquals("Empty preflight is not fixture ownership", 0, clears)
    }

    @Test
    fun cleanupRejectsUnownedChangedAndUnavailableMetadata() {
        val ownership = FixtureOwnership()
        assertFalse(ownership.mayClear(null))
        assertFalse(ownership.mayClear(1L))
        assertTrue(ownership.mayReplace(null))
        assertFalse(ownership.mayReplace(1L))
        ownership.record(1L)
        assertTrue(ownership.mayClear(1L))
        assertTrue(ownership.mayReplace(1L))
        assertFalse(ownership.mayClear(2L))
        assertFalse(ownership.mayReplace(2L))
        assertFalse(ownership.mayClear(null))
        assertFalse(ownership.mayReplace(null))
        ownership.record(null)
        assertFalse(ownership.mayClear(null))
        assertFalse(ownership.mayReplace(null))
        ownership.record(0L)
        assertFalse(ownership.mayClear(0L))
    }

    @Test
    fun parserPreservesPublicCharactersAndRejectsNonTextWithoutCoercion() {
        for (sample in prototypeSamples) verify(sample, parse(publicFixture(sample)))
    }

    @Test
    fun fixtureVerificationRejectsMissingAndReplacedSnapshots() {
        assertFalse(matchesFixture(null, Sample.UNICODE))
        assertFalse(matchesFixture(publicFixture(Sample.GUARD), Sample.UNICODE))
        assertFalse(matchesFixture(publicFixture(Sample.URI), Sample.INTENT))
        for (sample in prototypeSamples) assertTrue(matchesFixture(publicFixture(sample), sample))
    }

    @Test
    fun focusedExplicitButtonReadsOnlyFixturesOnAnOptedInCleanDevice() = withCleanDevice { activity, manager, ownership ->
        for (sample in prototypeSamples) {
            assumeTrue("Current item changed outside the fixture", ownership.mayReplace(manager.primaryClipDescription?.timestamp))
            manager.setPrimaryClip(publicFixture(sample))
            var acquired = false
            val probe = Probe({
                manager.primaryClip.also { snapshot ->
                    assertTrue("Platform did not return the fixed sample: ${sample.name}", matchesFixture(snapshot, sample))
                    acquired = true
                    ownership.record(snapshot?.description?.timestamp)
                }
            }, eligibility = { activity.hasWindowFocus() && !locked() })
            val button = Button(activity).apply {
                setOnClickListener {
                    probe.arm()
                }
            }
            // An explicit test-driven click is the only acquisition trigger.
            instrumentation.runOnMainSync { button.performClick() }
            // Instrumentation runs off the UI thread; platform acquisition never blocks the button callback.
            val draft = probe.read(activity.hasWindowFocus(), !locked())
            assertTrue("Sample acquisition was not exercised: ${sample.name}", acquired)
            verify(sample, draft)
        }
    }

    @Test
    fun publicApplicationSettingsCanBeOpenedWithoutReading() = withCleanDevice { activity, _, _ ->
        val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
        assertNotNull(intent.resolveActivity(context.packageManager))
        instrumentation.runOnMainSync { activity.startActivity(intent) }
        await { !activity.hasWindowFocus() }
        // Returning is deliberately not interpreted as permission approval.
        ClipboardDeviceTestSupport.shell("input keyevent KEYCODE_BACK")
        await { activity.hasWindowFocus() }
    }

    @Test
    fun defaultImeCanReadPublicSourceWhileNonDefaultBackgroundCannot() = withCleanDevice { activity, manager, ownership ->
        val driver = ClipboardDeviceTestSupport
        val previous = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val own = ComponentName(context, ZeroInputService::class.java).flattenToString()
        assumeTrue("Qualification comparison needs a different initial keyboard",
            ComponentName.unflattenFromString(previous.orEmpty()) != null && previous != own)
        val wasEnabled = driver.shell("ime list -s").lineSequence().any { it.trim() == own }
        fun isOwnIme() = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == own
        try {
            driver.shell("ime enable $own")
            driver.shell("ime set $own")
            await { isOwnIme() }
            driver.startSource()
            driver.click("Copy public fixture")
            await { !activity.hasWindowFocus() }
            val allowed = Probe({
                manager.primaryClip.also { snapshot ->
                    assertTrue("Source did not return the fixed sample", matchesFixture(snapshot, Sample.GUARD))
                    ownership.record(snapshot?.description?.timestamp)
                }
            }, eligibility = { isOwnIme() && !locked() })
            allowed.arm()
            verify(Sample.GUARD, allowed.read(true, !locked()))
            driver.shell("ime set $previous")
            await { Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == previous }
            assertFalse("Background-denial probe must not have a focused target window", activity.hasWindowFocus())
            val denied = Probe({ manager.primaryClip })
            denied.arm()
            val unexpected = denied.read(true, !locked())
            try { assertTrue("Non-default background acquisition must be unavailable", unexpected == null) }
            finally { unexpected?.close() }
        } finally {
            try {
                driver.shell("input keyevent KEYCODE_BACK")
                await { activity.hasWindowFocus() }
            } finally {
                driver.shell("ime set $previous")
                if (!wasEnabled) driver.shell("ime disable $own")
            }
        }
    }

    private fun verify(sample: Sample, request: ClipboardImportRequest?) {
        val expected = sample.expected
        if (expected == null) {
            try {
                assertTrue("Unsupported fixture must be rejected: ${sample.name}", request == null)
            } finally { request?.close() }
            return
        }
        val draft = checkNotNull(request) { "Public fixture rejected: ${sample.name}" }
        try {
            assertNull(draft.copyChars())
            assertNull(draft.copyForAi())
            assertFalse(draft.allowsAiImport)
            // Synthetic model transitions inspect fixed samples; not a system-auth test.
            assertTrue(draft.beginAuthentication())
            assertTrue(draft.authorize())
            val characters = checkNotNull(draft.copyChars())
            // Array equality assertions print mismatched characters into test diagnostics.
            try {
                assertTrue("Public fixture characters differ: ${sample.name}",
                    expected.length == characters.size && expected.indices.all { expected[it] == characters[it] })
            } finally { characters.fill('\u0000') }
        } finally { draft.close() }
        assertNull(draft.copyChars())
    }

    private fun withCleanDevice(
        onClear: (() -> Unit)? = null,
        ordinaryEditor: Boolean = false,
        block: (Activity, ClipboardManager, FixtureOwnership) -> Unit,
    ) {
        assumeTrue("Requires a dedicated disposable emulator and explicit runner argument",
            InstrumentationRegistry.getArguments().getString("publicClipboardPrototype") == "true")
        val graph = (context.applicationContext as ZeroInputApplication).graph
        assumeTrue("Disable the real clipboard guard first", !graph.clipboardGuardPreferences.options.listening)
        val activity = instrumentation.startActivitySync(Intent(context, InputFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("ordinary_fixture", ordinaryEditor))
        val manager = context.getSystemService(ClipboardManager::class.java)
        val ownership = FixtureOwnership()
        try {
            await { activity.hasWindowFocus() && !locked() }
            // No payload preflight, backup, or overwrite of an existing current item.
            assumeTrue("Existing or unavailable metadata: use a fresh emulator", manager.primaryClipDescription == null)
            block(activity, manager, ownership)
        } finally {
            try {
                // Timestamp comparison only reduces races; it is not an atomic ownership proof.
                if (activity.hasWindowFocus() && !locked() && ownership.mayClear(manager.primaryClipDescription?.timestamp)) {
                    if (onClear != null) onClear()
                    else if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
                    else manager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    private class FixtureOwnership {
        private var written = false
        private var timestamp: Long? = null
        fun record(value: Long?) { written = true; timestamp = value?.takeIf { it > 0 } }
        fun mayClear(current: Long?) = written && timestamp != null && timestamp == current
        fun mayReplace(current: Long?) = if (written) mayClear(current) else current == null
    }

    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertTrue("Public fixture window did not reach the requested state", condition())
    }

    /** Test-local single-use action, not the P2 production authorization contract. */
    private class Probe(
        private val source: () -> ClipData?,
        private val clock: () -> Long = SystemClock::elapsedRealtime,
        private val eligibility: () -> Boolean = { true },
    ) {
        private var generation = 0L
        private var expires: Long? = null
        fun arm() { generation++; expires = clock() + 5_000 }
        fun cancel() { generation++; expires = null }
        fun read(focused: Boolean, unlocked: Boolean): ClipboardImportRequest? {
            val deadline = expires ?: return null
            expires = null
            if (!focused || !unlocked || !eligibility() || clock() >= deadline) return null
            val token = generation
            val clip = try { source() } catch (_: SecurityException) { null }
            if (token != generation || !eligibility() || clock() >= deadline) return null
            val draft = parse(clip)
            if (token != generation || !eligibility() || clock() >= deadline) {
                draft?.close()
                return null
            }
            return draft
        }
    }

    private enum class Sample(val expected: String? = null) {
        UNICODE("  00123\t中文 English\nCRLF\r\n\u00a0！ https://example.invalid e\u0301 👨‍👩‍👧‍👦 👍🏽 🇨🇳  "),
        LIMIT("字".repeat(8192)), GUARD("public guard fixture"), HTML("public plain fallback"),
        EMPTY, BLANK, NUL, HIGH_SURROGATE, LOW_SURROGATE, OVER_LIMIT, URI, INTENT, MULTIPLE, WORDING_WINDOW,
    }

    companion object {
        private val prototypeSamples = Sample.entries - Sample.WORDING_WINDOW

        private fun matchesFixture(actual: ClipData?, sample: Sample): Boolean {
            val expected = publicFixture(sample)
            if (actual == null || actual.itemCount != expected.itemCount) return false
            if (actual.description.mimeTypeCount != expected.description.mimeTypeCount) return false
            for (index in 0 until expected.description.mimeTypeCount) {
                if (actual.description.getMimeType(index) != expected.description.getMimeType(index)) return false
            }
            for (index in 0 until expected.itemCount) {
                val item = actual.getItemAt(index)
                val fixture = expected.getItemAt(index)
                val text = item.text
                val fixedText = fixture.text
                if (fixedText == null) {
                    if (text != null) return false
                } else if (text == null || text.length != fixedText.length || fixedText.indices.any { text[it] != fixedText[it] }) {
                    return false
                }
                if (item.htmlText != fixture.htmlText || item.uri != fixture.uri) return false
                val fixedIntent = fixture.intent
                if (fixedIntent == null) {
                    if (item.intent != null) return false
                } else if (item.intent?.filterEquals(fixedIntent) != true || item.intent?.extras != null) return false
            }
            return true
        }

        private fun parse(clip: ClipData?): ClipboardImportRequest? {
            if (clip == null || clip.itemCount != 1) return null
            if (!clip.description.hasMimeType("text/plain") && !clip.description.hasMimeType("text/html")) return null
            val item = clip.getItemAt(0)
            if (item.uri != null || item.intent != null) return null
            // Never coerce text: that can open providers or synthesize URI/Intent strings.
            return item.text?.let { ClipboardImportText.parse(it) }
        }

        private fun publicFixture(sample: Sample): ClipData = when (sample) {
            Sample.UNICODE, Sample.LIMIT, Sample.GUARD -> ClipData.newPlainText("", checkNotNull(sample.expected))
            Sample.HTML -> ClipData.newHtmlText("", checkNotNull(sample.expected), "<b>public plain fallback</b>")
            Sample.EMPTY -> ClipData.newPlainText("", "")
            Sample.BLANK -> ClipData.newPlainText("", " \t\r\n")
            Sample.NUL -> ClipData.newPlainText("", "public\u0000fixture")
            Sample.HIGH_SURROGATE -> ClipData.newPlainText("", "\uD800")
            Sample.LOW_SURROGATE -> ClipData.newPlainText("", "\uDC00")
            Sample.OVER_LIMIT -> ClipData.newPlainText("", "字".repeat(8193))
            Sample.URI -> ClipData(ClipDescription("", arrayOf("text/plain")),
                ClipData.Item(Uri.parse("content://invalid.example/public-fixture")))
            Sample.INTENT -> ClipData.newIntent("", Intent("dev.zeroinput.PUBLIC_FIXTURE"))
            Sample.MULTIPLE -> ClipData.newPlainText("", "public first").apply { addItem(ClipData.Item("public second")) }
            Sample.WORDING_WINDOW -> ClipData.newPlainText("", InstrumentationRegistry.getInstrumentation().context.assets
                .open("stego-wording-window-public.txt").bufferedReader().use { it.readText() })
        }
    }
}
