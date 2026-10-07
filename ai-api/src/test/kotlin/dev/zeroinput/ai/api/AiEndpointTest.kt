package dev.zeroinput.ai.api

import org.junit.Assert.*
import org.junit.Test

class AiEndpointTest {
    @Test fun baseAndFullChatAddressesResolveToTheSameRoutes() {
        for (raw in listOf("https://example.test/v1", "https://example.test/v1/",
            "https://example.test/v1/chat/completions", "https://example.test/v1/chat/completions/")) {
            val endpoint = AiEndpoint.parse(raw)
            assertEquals("https://example.test/v1/chat/completions", endpoint.chat.toString())
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
