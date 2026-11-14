package dev.zeroinput.ime.ai.device

import dev.zeroinput.ime.ai.takeWholeCodePoints

import android.content.Intent
import android.content.pm.PackageManager
import dev.zeroinput.ai.api.DeviceAgentPolicy

internal data class DeviceAgentAppChoice(val label: String, val packageName: String)

/** Local launcher discovery only; no installed-app inventory is sent to the provider. */
internal object DeviceAgentAppChoices {
    fun load(manager: PackageManager, policy: DeviceAgentPolicy, ownPackage: String): List<DeviceAgentAppChoice> {
        val evaluator = DeviceAgentPolicyEvaluator(ownPackage)
        return manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .asSequence().take(512)
            .filter { it.activityInfo?.exported == true && it.activityInfo.enabled }
            .distinctBy { it.activityInfo.packageName }
            .filter { evaluator.checkTarget(policy, it.activityInfo.packageName).allowed }
            .map {
                DeviceAgentAppChoice(
                    it.loadLabel(manager).toString().filterNot(Char::isISOControl).takeWholeCodePoints(96),
                    it.activityInfo.packageName,
                )
            }.sortedWith(compareBy<DeviceAgentAppChoice> { it.label }.thenBy { it.packageName }).take(128).toList()
    }
}
