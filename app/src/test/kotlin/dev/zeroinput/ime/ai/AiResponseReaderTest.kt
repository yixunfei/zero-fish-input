package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiProviderError
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class AiResponseReaderTest {
    @Test fun malformedEmptyOversizedAndCoercedModelListsFailClosed() {
        for (wire in listOf("{}", "{\"data\":[]}", "{\"data\":[{\"id\":42}]}",
            "{\"data\":[{\"id\":\" \"}]}", "{\"data\":[{\"id\":\"x\\n\"}]}",
            "{\"data\":[{\"id\":\"x\"}]} trailing", "x".repeat(512 * 1024 + 1))) {
            assertThrows(AiProviderError.Response::class.java) { AiResponseReader.models(StringReader(wire), { false }) }
        }
        assertThrows(InterruptedException::class.java) { AiResponseReader.models(StringReader("{}"), { true }) }
    }
}
