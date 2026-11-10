package dev.zeroinput.ime.ai.device

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import dev.zeroinput.ai.api.DeviceAgentPolicy
import dev.zeroinput.ai.api.DeviceCapabilityScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class DeviceAgentAppChoicesTest {
    @Test fun appChoicesRespectTargetScopeExcludePrivateActivitiesAndDeduplicatePackages() {
        val manager = RuntimeEnvironment.getApplication().packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for ((name, exported) in listOf("public.allowed" to true, "public.allowed" to true,
                "public.denied" to true, "public.private" to false, "dev.zeroinput.ime.debug" to true)) {
            shadowOf(manager).addResolveInfoForIntent(intent, ResolveInfo().apply {
                nonLocalizedLabel = name
                activityInfo = ActivityInfo().apply {
                    packageName = name
                    this.name = "$name.MainActivity"
                    this.exported = exported
                    enabled = true
                    applicationInfo = ApplicationInfo().apply { packageName = name; enabled = true }
                }
            })
        }
        val policy = DeviceAgentPolicy(enabled = true, disclosureAccepted = true,
            scope = DeviceCapabilityScope.ALLOWLIST,
            allowedPackages = setOf("public.allowed", "public.private", "dev.zeroinput.ime.debug"))
        val choices = DeviceAgentAppChoices.load(manager, policy, "dev.zeroinput.ime.debug")
        assertEquals(listOf("public.allowed"), choices.map { it.packageName })
        assertTrue(DeviceAgentAppChoices.load(manager, policy.copy(enabled = false), "dev.zeroinput.ime.debug").isEmpty())
    }
}
