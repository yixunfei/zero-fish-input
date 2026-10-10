package dev.zeroinput.ime.settings

import android.content.Context
import dev.zeroinput.ai.api.AiProviderError
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ui.aiErrorMessage

internal fun aiSettingsError(context: Context, error: AiProviderError): String {
    if (error is AiProviderError.Policy) return context.getString(R.string.ai_setup_network_required)
    val text = context.getString(aiErrorMessage(error))
    val status = (error as? AiProviderError.Network)?.statusCode
    return if (status == null) text else context.getString(R.string.ai_http_failure, text, status)
}
