package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.DeviceAgentPolicy
import dev.zeroinput.userdata.AiConfiguration

/** A start click grants one short opportunity to launch with the reviewed configuration. */
internal class DeviceAgentLaunchGuard(
    private val createdAt: Long,
    private val dataGeneration: Long,
    private val configuration: AiConfiguration,
    private val policy: DeviceAgentPolicy,
) {
    private var consumed = false

    fun current(now: Long, generation: Long, config: AiConfiguration?, currentPolicy: DeviceAgentPolicy): Boolean =
        !consumed && now - createdAt in 0 until LIFETIME_MS && dataGeneration == generation &&
            configuration == config && policy == currentPolicy

    fun consume(now: Long, generation: Long, config: AiConfiguration?, currentPolicy: DeviceAgentPolicy): Boolean {
        if (!current(now, generation, config, currentPolicy)) return false
        consumed = true
        return true
    }

    fun cancel() { consumed = true }

    companion object { const val LIFETIME_MS = 10_000L }
}
