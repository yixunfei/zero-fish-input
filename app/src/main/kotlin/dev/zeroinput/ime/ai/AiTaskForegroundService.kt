package dev.zeroinput.ime.ai

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.zeroinput.ime.ZeroInputApplication

/** Non-sticky liveness only. All task authority stays in the in-memory runtime. */
class AiTaskForegroundService : Service() {
    private var token: String? = null
    private var activeStartId = 0
    private val runtime get() = (application as ZeroInputApplication).graph.aiTaskRuntime
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val value = intent?.getStringExtra(AiRunNotifications.TOKEN)
        if (value == null || !runtime.acceptsService(value)) {
            if (!runtime.acceptsService(token)) stopSelf(startId)
            return START_NOT_STICKY
        }
        token = value; activeStartId = startId
        try {
            val notification = runtime.foregroundNotification(value)
            if (Build.VERSION.SDK_INT >= 34) startForeground(AiRunNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(AiRunNotifications.ID, notification)
            if (!runtime.serviceStarted(value)) stopSelf(startId)
        } catch (_: RuntimeException) { runtime.revoke(); stopSelf(startId) }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int) { if (startId == activeStartId) { runtime.revoke(); stopSelf() } }
    override fun onTimeout(startId: Int, fgsType: Int) { if (startId == activeStartId) { runtime.revoke(); stopSelf() } }
    override fun onTaskRemoved(rootIntent: Intent?) { runtime.revoke(); stopSelf() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE); runtime.serviceLost(token); token = null; super.onDestroy()
    }
}
