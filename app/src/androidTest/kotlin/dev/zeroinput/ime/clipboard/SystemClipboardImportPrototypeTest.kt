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
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.ZeroInputService
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport
import dev.zeroinput.ime.testing.InputFixtureActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P1 only: never packaged in the IME; no vault, authentication, cleanup or listener wiring. */
@RunWith(AndroidJUnit4::class)
class SystemClipboardImportPrototypeTest {
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
        for (sample in Sample.entries) verify(sample, parse(publicFixture(sample)))
    }

    @Test
    fun fixtureVerificationRejectsMissingAndReplacedSnapshots() {
        assertFalse(matchesFixture(null, Sample.UNICODE))
        assertFalse(matchesFixture(publicFixture(Sample.GUARD), Sample.UNICODE))
        assertFalse(matchesFixture(publicFixture(Sample.URI), Sample.INTENT))
        for (sample in Sample.entries) assertTrue(matchesFixture(publicFixture(sample), sample))
    }

    @Test
    fun focusedExplicitButtonReadsOnlyFixturesOnAnOptedInCleanDevice() = withCleanDevice { activity, manager, ownership ->
        for (sample in Sample.entries) {
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
        block: (Activity, ClipboardManager, FixtureOwnership) -> Unit,
    ) {
        assumeTrue("Requires a dedicated disposable emulator and explicit runner argument",
            InstrumentationRegistry.getArguments().getString("publicClipboardPrototype") == "true")
        val graph = (context.applicationContext as ZeroInputApplication).graph
        assumeTrue("Disable the real clipboard guard first", !graph.clipboardGuardPreferences.options.listening)
        val activity = instrumentation.startActivitySync(Intent(context, InputFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
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
        EMPTY, BLANK, NUL, HIGH_SURROGATE, LOW_SURROGATE, OVER_LIMIT, URI, INTENT, MULTIPLE,
    }

    companion object {
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
        }
    }
}
