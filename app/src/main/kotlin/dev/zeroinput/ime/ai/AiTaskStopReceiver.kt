package dev.zeroinput.ime.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.zeroinput.ime.ZeroInputApplication

class AiTaskStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AiRunNotifications.STOP) return
        val token = intent.getStringExtra(AiRunNotifications.TOKEN)?.takeIf { it.length <= 64 } ?: return
        if (intent.dataString != "zeroinput-task://$token") return
        (context.applicationContext as ZeroInputApplication).graph.aiTaskRuntime.stop(token)
    }
}
