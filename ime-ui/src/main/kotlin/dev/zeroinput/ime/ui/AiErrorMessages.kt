package dev.zeroinput.ime.ui

import dev.zeroinput.ai.api.AiNetworkFailure
import dev.zeroinput.ai.api.AiProviderError

fun aiErrorMessage(error: AiProviderError): Int = when (error) {
    is AiProviderError.Policy -> R.string.ai_policy_unavailable
    is AiProviderError.Storage -> R.string.ai_storage_failed
    is AiProviderError.Configuration -> R.string.ai_configuration_invalid
    is AiProviderError.Response -> when (error.reason) {
        dev.zeroinput.ai.api.AiResponseFailure.INVALID -> R.string.ai_response_invalid
        dev.zeroinput.ai.api.AiResponseFailure.OUTPUT_LIMIT -> R.string.ai_output_limit
        dev.zeroinput.ai.api.AiResponseFailure.EMPTY -> R.string.ai_output_empty
    }
    is AiProviderError.Network -> when (error.reason) {
        AiNetworkFailure.CONNECTION -> R.string.ai_connection_failed
        AiNetworkFailure.DNS -> R.string.ai_dns_failed
        AiNetworkFailure.TLS -> R.string.ai_tls_failed
        AiNetworkFailure.REDIRECT -> R.string.ai_redirect_failed
        AiNetworkFailure.TIMEOUT -> R.string.ai_timeout
        AiNetworkFailure.AUTHENTICATION -> R.string.ai_authentication_failed
        AiNetworkFailure.MODEL_OR_ENDPOINT -> R.string.ai_model_unavailable
        AiNetworkFailure.RATE_LIMIT -> R.string.ai_rate_limited
        AiNetworkFailure.SERVICE -> R.string.ai_service_unavailable
        AiNetworkFailure.REQUEST -> R.string.ai_request_rejected
    }
}
