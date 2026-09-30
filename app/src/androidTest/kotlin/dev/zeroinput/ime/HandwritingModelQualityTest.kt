package dev.zeroinput.ime

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.model.OfflineHandwritingRecognizer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HandwritingModelQualityTest {
    private data class Fixture(val label: String, val strokes: List<FloatArray>)
    private data class Metrics(val count: Int, val top1: Int, val top16: Int, val loadMs: Long,
        val medianMs: Long, val p95Ms: Long, val javaHeapDelta: Long, val nativeHeapDelta: Long)

    @Test fun independentPublicTraditionalStrokesMeetQualityFloorAndReportDeviceCosts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val worker = Executors.newSingleThreadExecutor()
        val metrics = try {
            worker.submit<Metrics> { measure() }.get(180, TimeUnit.SECONDS)
        } finally { worker.shutdownNow() }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("handwriting_quality", "Public independent traditional stroke corpus")
            putInt("handwriting_count", metrics.count)
            putInt("handwriting_top1", metrics.top1)
            putInt("handwriting_top16", metrics.top16)
            putLong("handwriting_load_ms", metrics.loadMs)
            putLong("handwriting_median_ms", metrics.medianMs)
            putLong("handwriting_p95_ms", metrics.p95Ms)
            putLong("handwriting_java_heap_delta_bytes", metrics.javaHeapDelta)
            putLong("handwriting_native_heap_delta_bytes", metrics.nativeHeapDelta)
        })
        assertEquals(20, metrics.count)
        assertTrue("Independent traditional top-1 quality regressed", metrics.top1 >= 12)
        assertTrue("Independent traditional candidate coverage regressed", metrics.top16 >= 17)
    }

    private fun measure(): Metrics {
        val fixtures = fixtures()
        val runtime = Runtime.getRuntime()
        val javaBefore = runtime.totalMemory() - runtime.freeMemory()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        val started = SystemClock.elapsedRealtime()
        try {
            OfflineHandwritingRecognizer(InstrumentationRegistry.getInstrumentation().targetContext).use { recognizer ->
                val loaded = SystemClock.elapsedRealtime() - started
                val javaDelta = runtime.totalMemory() - runtime.freeMemory() - javaBefore
                val nativeDelta = Debug.getNativeHeapAllocatedSize() - nativeBefore
                assertTrue(recognizer.recognize(fixtures.first().strokes) { true }.isEmpty())
                val times = ArrayList<Long>()
                var top1 = 0
                var top16 = 0
                fixtures.forEach { fixture ->
                    val began = SystemClock.elapsedRealtime()
                    val candidates = recognizer.recognize(fixture.strokes)
                    times.add(SystemClock.elapsedRealtime() - began)
                    if (candidates.firstOrNull() == fixture.label) top1++
                    if (fixture.label in candidates) top16++
                    assertTrue(candidates.size <= 16)
                }
                times.sort()
                return Metrics(fixtures.size, top1, top16, loaded, times[times.size / 2], times[(times.size * 95 / 100).coerceAtMost(times.lastIndex)],
                    javaDelta, nativeDelta)
            }
        } finally { fixtures.forEach { fixture -> fixture.strokes.forEach { it.fill(0f) } } }
    }

    private fun fixtures(): List<Fixture> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        check(assets.list("handwriting")?.contains("independent-traditional.json") == true) {
            "Run python tools/prepare-handwriting-model.py --include-quality-fixtures before building Android tests"
        }
        val json = assets.open("handwriting/independent-traditional.json").bufferedReader().use { JSONObject(it.readText()) }
        val samples = json.getJSONArray("samples")
        check(samples.length() == 20) { "Invalid public handwriting fixture count" }
        return List(samples.length()) { index ->
            val sample = samples.getJSONObject(index)
            val strokes = sample.getJSONArray("strokes")
            Fixture(sample.getString("label"), List(strokes.length()) { strokeIndex ->
                val points = strokes.getJSONArray(strokeIndex)
                FloatArray(points.length()) { point -> points.getDouble(point).toFloat() }
            })
        }
    }
}
