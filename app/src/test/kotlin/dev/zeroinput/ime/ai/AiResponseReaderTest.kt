package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiProviderError
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class AiResponseReaderTest {
    @Test fun nestingChecksIgnoreEscapedQuotesAndBracketsInsideStrings() {
        val id = "[".repeat(40) + "\\\"" + "]".repeat(40)
        val wire = org.json.JSONObject().put("data", org.json.JSONArray().put(org.json.JSONObject().put("id", id))).toString()
        assertEquals(listOf(id), AiResponseReader.models(StringReader(wire), { false }))
    }

    @Test fun deeplyNestedModelCatalogFailsBeforeRecursiveParsing() {
        val wire = "{\"data\":" + "[".repeat(10_000) + "0" + "]".repeat(10_000) + "}"
        assertThrows(AiProviderError.Response::class.java) { AiResponseReader.models(StringReader(wire), { false }) }
    }

    @Test fun commentAndSingleQuoteExtensionsCannotBypassNestingValidation() {
        for (wire in listOf("{\"data\":/* } */[]}", "{'data':[]}", "{# }\n\"data\":[]}")) {
            assertThrows(AiProviderError.Response::class.java) { AiResponseReader.models(StringReader(wire), { false }) }
        }
    }

    @Test fun malformedEmptyOversizedAndCoercedModelListsFailClosed() {
        for (wire in listOf("{}", "{\"data\":[]}", "{\"data\":[{\"id\":42}]}",
            "{\"data\":[{\"id\":\" \"}]}", "{\"data\":[{\"id\":\"x\\n\"}]}",
            "{\"data\":[{\"id\":\"x\"}]} trailing", "x".repeat(512 * 1024 + 1))) {
            assertThrows(AiProviderError.Response::class.java) { AiResponseReader.models(StringReader(wire), { false }) }
        }
        assertThrows(InterruptedException::class.java) { AiResponseReader.models(StringReader("{}"), { true }) }
    }
}
