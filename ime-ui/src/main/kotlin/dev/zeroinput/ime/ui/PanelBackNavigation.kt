package dev.zeroinput.ime.ui

import android.os.Build
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi

/** A secondary panel takes precedence over the platform's predictive IME dismissal. */
internal class PanelBackNavigation(
    private val view: View,
    private val canNavigate: () -> Boolean,
    private val navigate: () -> Unit,
) {
    private var registration: Registration? = null

    fun refresh() {
        if (Build.VERSION.SDK_INT < 33) return
        val dispatcher = if (view.isAttachedToWindow && canNavigate()) view.findOnBackInvokedDispatcher() else null
        if (registration?.dispatcher === dispatcher) return
        clear()
        if (dispatcher != null) registration = Registration(dispatcher, navigate)
    }

    fun clear() {
        if (Build.VERSION.SDK_INT >= 33) registration?.close()
        registration = null
    }

    @RequiresApi(33)
    private class Registration(val dispatcher: OnBackInvokedDispatcher, navigate: () -> Unit) {
        private val callback = OnBackInvokedCallback(navigate)
        init { dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback) }
        fun close() { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}
