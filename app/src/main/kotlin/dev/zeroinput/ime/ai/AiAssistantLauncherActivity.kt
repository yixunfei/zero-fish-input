package dev.zeroinput.ime.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Public launcher gateway. Incoming content, grants and task commands never cross it. */
class AiAssistantLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (accepts(intent)) startActivity(Intent(this, AiAssistantActivity::class.java))
        finish()
    }

    companion object {
        internal fun accepts(value: Intent): Boolean = value.action == Intent.ACTION_MAIN &&
            value.categories == setOf(Intent.CATEGORY_LAUNCHER) && value.data == null && value.type == null
    }
}
