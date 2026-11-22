package dev.zeroinput.ime.textstego

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ime.R
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.ime.core.stego.VerseTestCapacityFailure
import dev.zeroinput.ime.core.stego.VerseTestFormatFailure
import dev.zeroinput.ime.input.isPrivateDraftDeviceAvailable
import dev.zeroinput.ime.management.ManagementTaskRunner

/** Foreground Debug page for trying the frozen P2 profile with public samples. */
class VerseTestActivity : AppCompatActivity() {
    private lateinit var workbench: VerseTestWorkbench
    private lateinit var generate: MaterialButton
    private lateinit var reopen: MaterialButton
    private lateinit var host: MaterialButton
    private lateinit var status: TextView
    private lateinit var carrierHeading: TextView
    private lateinit var carrier: TextView
    private lateinit var recoveredHeading: TextView
    private lateinit var recovered: TextView
    private var last: VerseTestCarrier? = null
    private var resumed = false
    private var busy = false
    private val runner = ManagementTaskRunner(
        worker = publicWorker,
        post = { runOnUiThread(it) },
        isActive = { resumed && !isFinishing && isPrivateDraftDeviceAvailable(this) },
        onBusy = {
            busy = it
            if (it) status.setText(R.string.verse_test_working)
            updateButtons()
        },
        onFailure = {
            show(null, null)
            status.setText(if (it is VerseTestCapacityFailure) R.string.verse_test_capacity else R.string.verse_test_failed)
        },
    )
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { clear() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        workbench = VerseTestWorkbench(assets)
        setContentView(buildPage())
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        clear()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateButtons()
    }

    override fun onPause() {
        resumed = false
        clear()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        runner.close()
        super.onDestroy()
    }

    private fun buildPage(): View {
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.verse_test_title)
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.navigate_up)
            setNavigationOnClickListener { finish() }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
            addView(label(R.string.verse_test_intro))
            generate = button(R.id.verse_test_generate, R.string.verse_test_generate) { generate() }
            reopen = button(R.id.verse_test_reopen, R.string.verse_test_reopen) { last?.let { open(it.text) } }
            host = button(R.id.verse_test_open_host, R.string.verse_test_open_host) { open(null) }
            addView(generate)
            addView(reopen)
            addView(host)
            status = label(R.string.verse_test_idle).apply {
                id = R.id.verse_test_status
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(status)
            carrierHeading = label(R.string.verse_test_carrier).apply { ViewCompat.setAccessibilityHeading(this, true) }
            carrier = label().apply { id = R.id.verse_test_carrier }
            recoveredHeading = label(R.string.verse_test_recovered).apply { ViewCompat.setAccessibilityHeading(this, true) }
            recovered = label().apply { id = R.id.verse_test_recovered }
            addView(carrierHeading)
            addView(carrier)
            addView(recoveredHeading)
            addView(recovered)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isSaveFromParentEnabled = false
            addView(toolbar)
            addView(ScrollView(this@VerseTestActivity).apply {
                isFillViewport = true
                addView(body)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bounds = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(bounds.left, bounds.top, bounds.right, bounds.bottom)
                insets
            }
        }
    }

    private fun generate() {
        if (busy) return
        val worker = workbench
        runner.execute(work = {
            val raw = worker.sample()
            try { worker.seal(raw) } finally { raw.fill(0) }
        }) { show(it, null) }
    }

    private fun open(local: String?) {
        if (busy) return
        val worker = workbench
        runner.execute(work = {
            val (value, raw) = worker.open(local ?: worker.hostSample())
            var expected: ByteArray? = null
            try {
                expected = worker.sample()
                if (!raw.contentEquals(expected)) throw VerseTestFormatFailure()
                value to raw.toString(Charsets.UTF_8)
            } finally { raw.fill(0); expected?.fill(0) }
        }) { (value, original) -> show(value, original) }
    }

    private fun show(value: VerseTestCarrier?, original: String?) {
        last = value
        carrier.text = value?.text
        recovered.text = original
        carrierHeading.visibility = if (value == null) View.GONE else View.VISIBLE
        carrier.visibility = carrierHeading.visibility
        recoveredHeading.visibility = if (original == null) View.GONE else View.VISIBLE
        recovered.visibility = recoveredHeading.visibility
        if (value != null) {
            val outcome = if (original != null) R.string.verse_test_verified else R.string.verse_test_generated
            val capacity = if (value.passesTarget) R.string.verse_test_target_met else R.string.verse_test_target_missed
            status.text = getString(R.string.verse_test_sizes, getString(outcome), value.originalBytes,
                value.envelopeBytes, value.utf8Bytes, value.text.length, getString(capacity))
        }
        updateButtons()
    }

    private fun clear() {
        runner.cancel()
        show(null, null)
        status.setText(R.string.verse_test_idle)
    }

    private fun updateButtons() {
        val canClick = resumed && !busy && isPrivateDraftDeviceAvailable(this)
        generate.isEnabled = canClick
        host.isEnabled = canClick
        reopen.isEnabled = canClick && last != null
    }

    private fun button(id: Int, title: Int, action: () -> Unit) = MaterialButton(this).apply {
        this.id = id
        setText(title)
        minHeight = dp(48)
        isSaveEnabled = false
        filterTouchesWhenObscured = true
        setOnClickListener { action() }
    }

    private fun label(title: Int = 0) = TextView(this).apply {
        if (title != 0) setText(title)
        setPadding(0, dp(8), 0, dp(8))
        textSize = 16f
        isSaveEnabled = false
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    private companion object {
        val publicWorker = BoundedExecutors.singleThread("zeroinput-public-verse", queueCapacity = 1)
    }
}
