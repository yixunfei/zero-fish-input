package dev.zeroinput.ai.api

import org.junit.Assert.*
import org.junit.Test

class AiEndpointTest {
    @Test fun protocolsKeepProxyPrefixesAndNeverAppendToAnotherProtocolsRoute() {
        for (protocol in AiProtocol.entries) {
            for (address in listOf("https://example.test/proxy/v1", "https://example.test/proxy/v1/${protocol.route}/")) {
                val parsed = AiEndpoint.parse(address, protocol)
                assertEquals("/proxy/v1/${protocol.route}", parsed.generation.path)
                assertEquals("/proxy/v1/models", parsed.models.path)
            }
            for (other in AiProtocol.entries.filter { it != protocol })
                assertThrows(AiProviderError.Configuration::class.java) {
                    AiEndpoint.parse("https://example.test/v1/${other.route}", protocol)
                }
        }
        assertEquals("/v1/chat/completions", AiEndpoint.parse(" https://example.test/v1\u00a0").generation.path)
    }

    @Test fun catalogUrlsAndEncodedOrEmptyPathSegmentsCannotBecomeGenerationBases() {
        for (path in listOf("/v1/models", "/v1//", "/v1/%2e%2e", "/v1/%2f", "/v1/%5c", "/v1/%20"))
            assertThrows(AiProviderError.Configuration::class.java) { AiEndpoint.parse("https://example.test$path") }
    }
    @Test fun baseAndFullChatAddressesResolveToTheSameRoutes() {
        for (raw in listOf("https://example.test/v1", "https://example.test/v1/",
            "https://example.test/v1/chat/completions", "https://example.test/v1/chat/completions/")) {
            val endpoint = AiEndpoint.parse(raw)
            assertEquals("https://example.test/v1/chat/completions", endpoint.generation.toString())
            assertEquals("https://example.test/v1/models", endpoint.models.toString())
        }
        assertEquals("https://example.test/proxy/v2/models", AiEndpoint.parse("https://example.test/proxy/v2").models.toString())
        assertEquals("https://example.test/v1/models", AiEndpoint.parse("https://example.test").models.toString())
    }

    @Test fun unsafeOrAmbiguousAddressesAreRejectedWithoutExposingTheInput() {
        for (raw in listOf("http://example.test/v1", "https://secret@example.test/v1",
            "https://example.test/v1?secret=1", "https://example.test/v1#secret", "https://example.test:0/v1",
            "https://example.test/a/../v1", "https://example.test/v1\\secret", "https://")) {
            val error = assertThrows(AiProviderError.Configuration::class.java) { AiEndpoint.parse(raw) }
            assertFalse(error.toString().contains("secret"))
        }
    }
}
