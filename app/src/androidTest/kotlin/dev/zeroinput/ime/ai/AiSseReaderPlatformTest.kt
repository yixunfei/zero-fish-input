package dev.zeroinput.ime.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zeroinput.ai.api.AiProviderError
import java.io.StringReader
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiSseReaderPlatformTest {
    @Test fun nonStringContentIsRejectedInsteadOfCoercedByAndroidJson() {
        for (content in listOf("42", "true", "{}", "[]")) {
            val stream = "data: {\"choices\":[{\"delta\":{\"content\":$content}}]}\n\ndata: [DONE]\n"
            assertThrows(AiProviderError.Response::class.java) {
                AiSseReader().read(StringReader(stream).buffered(), { false }, {})
            }
        }
    }
}
