package dev.zeroinput.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.model.OfflineHandwritingRecognizer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HandwritingModelTest {
    @Test fun bundledModelRecognizesPublicConstructedStrokeOffMainThread() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<List<String>> {
                OfflineHandwritingRecognizer(context).use { recognizer ->
                    recognizer.recognize(listOf(
                        floatArrayOf(0.22f, 0.5f, 0.78f, 0.5f),
                        floatArrayOf(0.5f, 0.17f, 0.5f, 0.83f),
                    ))
                }
            }.get(60, TimeUnit.SECONDS)
            assertTrue(result.isNotEmpty())
            assertTrue("The constructed character should be selectable", "十" in result)
            assertTrue(result.all { value ->
                value.codePointCount(0, value.length) == 1 &&
                    Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.HAN
            })
        } finally {
            worker.shutdownNow()
        }
    }
}
