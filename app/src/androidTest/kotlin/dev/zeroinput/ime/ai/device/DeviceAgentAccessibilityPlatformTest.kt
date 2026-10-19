package dev.zeroinput.ime.ai.device

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.DeviceActionCall
import dev.zeroinput.ai.api.DeviceActionCategory
import dev.zeroinput.ai.api.DeviceActionResult
import dev.zeroinput.ai.api.DeviceActionResultStatus
import dev.zeroinput.ai.api.DeviceAgentLimits
import dev.zeroinput.ai.api.DeviceApprovalMode
import dev.zeroinput.ai.api.DeviceCapabilityScope
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiProviderProfile
import dev.zeroinput.ime.ZeroInputApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises the operation-capable accessibility service against a public test
 * page. The fixture contains fixed text only; it is never used as a source of
 * personal or clipboard data.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class DeviceAgentAccessibilityPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(
        android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    )
    private val context get() = instrumentation.targetContext
    private val graph get() = (context.applicationContext as ZeroInputApplication).graph
    private val fixturePackage get() = instrumentation.context.packageName
    private val fixtureActivity =
        "$fixturePackage/dev.zeroinput.ime.ai.page.ExternalPageFixture"

    @Test
    fun snapshotRedactsSensitiveFieldsAndApprovedClickIsSingleUse() = withFixture {
        val policy = graph.deviceAgentPolicySnapshot()
        val owner = requireNotNull(onMain {
            graph.deviceAgentBroker.beginTask(fixturePackage, policy, "platform-click")
        })
        var frame: dev.zeroinput.ai.api.DeviceScreenSnapshot? = null
        var token = -1L
        val captured = CountDownLatch(1)
        onMain {
            graph.deviceAgentBroker.capture(owner) { value, result ->
                token = value
                frame = result
                captured.countDown()
            }
        }
        assertTrue("Accessibility snapshot did not arrive", captured.await(12, TimeUnit.SECONDS))
        val snapshot = requireNotNull(frame)
        try {
            assertEquals(fixturePackage, snapshot.packageName)
            assertTrue(snapshot.elements.any { it.text == "Public first reference" })
            assertTrue(snapshot.elements.any { it.text.equals("Public click target", ignoreCase = true) })
            assertTrue(snapshot.elements.any { it.editable && it.text == null })
            assertTrue(snapshot.elements.any { it.password && it.text == null })
            assertTrue(snapshot.elements.none { it.text?.contains("Public password fixture") == true })
            assertTrue(snapshot.elements.none { it.text?.contains("Public editable fixture") == true })

            val jpeg = requireNotNull(snapshot.useScreenshot { it?.copyOf() })
            try {
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                assertNotNull("Screenshot bytes must decode as an image", bitmap)
                requireNotNull(bitmap).let {
                    assertTrue(it.width > 0 && it.height > 0)
                    assertTrue(it.width <= context.resources.displayMetrics.widthPixels)
                    assertTrue(it.height <= context.resources.displayMetrics.heightPixels)
                    assertTrue(it.width.toLong() * it.height <= DeviceAgentLimits.MAX_SCREENSHOT_PIXELS)
                    it.recycle()
                }
                assertTrue(jpeg.size <= DeviceAgentLimits.MAX_SCREENSHOT_BYTES)
                assertEquals(0xff, jpeg[0].toInt() and 0xff)
                assertEquals(0xd8, jpeg[1].toInt() and 0xff)
            } finally {
                jpeg.fill(0)
            }

            val target = snapshot.elements.first {
                it.text.equals("Public click target", ignoreCase = true) && it.clickable && it.enabled
            }
            val call = DeviceActionCall(
                callId = "platform-click",
                toolName = "click",
                snapshotId = snapshot.snapshotId,
                arguments = mapOf("element_ref" to target.ref),
                round = 0,
            )
            var approvalResult: Boolean? = null
            assertTrue(onMain {
                graph.deviceAgentBroker.presentApproval(
                    owner,
                    call.callId,
                    "Test approval",
                    "Click the public fixture button",
                    call,
                    token,
                ) { approvalResult = it }
            })
            assertTrue(onMain { graph.deviceAgentBroker.decide(owner, call.callId, true) })
            assertFalse(onMain { graph.deviceAgentBroker.decide(owner, call.callId, true) })
            assertEquals(true, approvalResult)

            var result: DeviceActionResult? = null
            val completed = CountDownLatch(1)
            onMain {
                graph.deviceAgentBroker.perform(owner, token, call, snapshot) {
                    result = it
                    completed.countDown()
                }
            }
            assertTrue("Approved click did not complete", completed.await(12, TimeUnit.SECONDS))
            assertEquals(DeviceActionResultStatus.SUCCESS, requireNotNull(result).status)
            var replay: DeviceActionResult? = null
            onMain { graph.deviceAgentBroker.perform(owner, token, call, snapshot) { replay = it } }
            assertEquals(DeviceActionResultStatus.EXPIRED, requireNotNull(replay).status)
        } finally {
            snapshot.clearScreenshot()
            onMain { graph.deviceAgentBroker.revokeTask(owner) }
        }
    }

    @Test
    fun foregroundSwitchInvalidatesLeaseBeforeExecution() = withFixture {
        val owner = requireNotNull(onMain {
            graph.deviceAgentBroker.beginTask(fixturePackage, graph.deviceAgentPolicySnapshot(), "platform-home")
        })
        try {
            shell("input keyevent 3")
            await("Foreground package change did not expire the task") {
                !onMain { graph.deviceAgentBroker.isCurrent(owner) }
            }
        } finally {
            onMain { graph.deviceAgentBroker.revokeTask(owner) }
        }
    }

    @Test
    fun screenOffInvalidatesLease() = withFixture {
        val owner = requireNotNull(onMain {
            graph.deviceAgentBroker.beginTask(fixturePackage, graph.deviceAgentPolicySnapshot(), "platform-lock")
        })
        try {
            shell("input keyevent 26")
            await("Screen-off did not expire the task") {
                !onMain { graph.deviceAgentBroker.isCurrent(owner) }
            }
            shell("input keyevent 26")
        } finally {
            onMain { graph.deviceAgentBroker.revokeTask(owner) }
        }
    }

    @Test
    fun serviceRevocationInvalidatesLease() = withFixture {
        val owner = requireNotNull(onMain {
            graph.deviceAgentBroker.beginTask(fixturePackage, graph.deviceAgentPolicySnapshot(), "platform-service")
        })
        try {
            shell("settings delete secure enabled_accessibility_services")
            await("Accessibility service revocation did not disconnect the broker") {
                !onMain { graph.deviceAgentBroker.connected } && !onMain { graph.deviceAgentBroker.isCurrent(owner) }
            }
        } finally {
            onMain { graph.deviceAgentBroker.revokeTask(owner) }
        }
    }

    private fun withFixture(test: () -> Unit) {
        val initialConfig = readConfiguration()
        val initialSettings = InitialSettings(
            enabled = graph.settings.deviceAgentEnabled,
            disclosure = graph.settings.deviceAgentDisclosureAccepted,
            scope = graph.settings.deviceAgentScope,
            approval = graph.settings.deviceAgentApprovalMode,
            screenshots = graph.settings.deviceAgentAllowScreenshots,
            packages = graph.settings.deviceAgentAllowedPackages,
            categories = graph.settings.deviceAgentAllowedCategories,
            learning = graph.settings.learningEnabled,
            incognito = graph.settings.incognitoMode,
        )
        val resolver = context.contentResolver
        val previousServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val previousAccessibility = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val previousIme = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ownService = "$contextPackage/dev.zeroinput.ime.ai.device.DeviceAgentAccessibilityService"
        try {
            configure(
                AiConfiguration(
                    enabled = true,
                    networkAllowed = true,
                    providers = listOf(
                        AiProviderProfile(
                            id = "platform-fixture",
                            name = "Platform fixture",
                            endpoint = "https://example.invalid/v1",
                            apiKey = "platform-fixture-key",
                            models = listOf("platform-fixture-model"),
                            selectedModel = "platform-fixture-model",
                            imageModels = setOf("platform-fixture-model"),
                        ),
                    ),
                    selectedProviderId = "platform-fixture",
                ),
            )
            onMain {
                graph.settings.learningEnabled = true
                graph.settings.incognitoMode = false
                graph.settings.deviceAgentEnabled = true
                graph.settings.deviceAgentDisclosureAccepted = true
                graph.settings.deviceAgentScope = DeviceCapabilityScope.FULL_SUPPORT
                graph.settings.deviceAgentApprovalMode = DeviceApprovalMode.EACH_ACTION
                graph.settings.deviceAgentAllowScreenshots = true
                graph.settings.deviceAgentAllowedPackages = emptySet()
                graph.settings.deviceAgentAllowedCategories = DeviceActionCategory.entries.toSet()
            }
            shell("settings put secure enabled_accessibility_services $ownService")
            shell("settings put secure accessibility_enabled 1")
            shell("ime enable $contextPackage/dev.zeroinput.ime.ZeroInputService")
            shell("ime set $contextPackage/dev.zeroinput.ime.ZeroInputService")
            shell("am start -W -n $fixtureActivity -f 0x10008000")
            await("Device accessibility service did not connect to the public fixture") {
                onMain {
                    graph.deviceAgentBroker.connected &&
                        graph.deviceAgentBroker.currentWindow()?.packageName == fixturePackage
                }
            }
            instrumentation.waitForIdleSync()
            automation.waitForIdle(300, 5_000)
            test()
        } finally {
            onMain { graph.deviceAgentBroker.invalidate() }
            shell("input keyevent 4")
            if (previousServices.isNullOrBlank() || previousServices == "null") {
                shell("settings delete secure enabled_accessibility_services")
            } else {
                shell("settings put secure enabled_accessibility_services $previousServices")
            }
            shell("settings put secure accessibility_enabled $previousAccessibility")
            if (!previousIme.isNullOrBlank() && previousIme != "null") shell("ime set $previousIme")
            onMain {
                graph.settings.deviceAgentEnabled = initialSettings.enabled
                graph.settings.deviceAgentDisclosureAccepted = initialSettings.disclosure
                graph.settings.deviceAgentScope = initialSettings.scope
                graph.settings.deviceAgentApprovalMode = initialSettings.approval
                graph.settings.deviceAgentAllowScreenshots = initialSettings.screenshots
                graph.settings.deviceAgentAllowedPackages = initialSettings.packages
                graph.settings.deviceAgentAllowedCategories = initialSettings.categories
                graph.settings.learningEnabled = initialSettings.learning
                graph.settings.incognitoMode = initialSettings.incognito
            }
            configure(initialConfig)
        }
    }

    private val contextPackage: String get() = context.packageName

    private data class InitialSettings(
        val enabled: Boolean,
        val disclosure: Boolean,
        val scope: DeviceCapabilityScope,
        val approval: DeviceApprovalMode,
        val screenshots: Boolean,
        val packages: Set<String>,
        val categories: Set<DeviceActionCategory>,
        val learning: Boolean,
        val incognito: Boolean,
    )

    private fun readConfiguration(): AiConfiguration {
        val done = CountDownLatch(1)
        var loaded: AiConfiguration? = null
        var failure: Throwable? = null
        graph.readAiConfiguration { result ->
            result.onSuccess { loaded = it }.onFailure { failure = it }
            done.countDown()
        }
        assertTrue("AI configuration could not be loaded", done.await(12, TimeUnit.SECONDS))
        failure?.let { throw AssertionError("AI configuration could not be loaded", it) }
        return requireNotNull(loaded)
    }

    private fun configure(value: AiConfiguration) {
        val done = CountDownLatch(1)
        var saved = false
        graph.updateAiConfiguration(value) { saved = it; done.countDown() }
        assertTrue("AI configuration was not saved", done.await(12, TimeUnit.SECONDS))
        assertTrue("AI configuration update failed", saved)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        assertTrue(message, condition())
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }
}
