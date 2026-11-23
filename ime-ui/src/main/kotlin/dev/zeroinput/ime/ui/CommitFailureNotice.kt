package dev.zeroinput.ime.ui

import android.content.Context
import android.widget.Toast
import dev.zeroinput.ime.core.CommitTextResult
import dev.zeroinput.ime.core.InputSessionState

/** Generic feedback only: no draft, editor or candidate content enters a notification. */
internal class CommitFailureNotice(private val context: Context) {
    private var toast: Toast? = null

    fun render(state: InputSessionState?) {
        clear()
        val message = when (state?.commitFailure) {
            CommitTextResult.REJECTED -> R.string.commit_rejected
            CommitTextResult.UNKNOWN -> R.string.commit_uncertain
            else -> when (state?.directCommitFailure) {
                CommitTextResult.REJECTED -> R.string.direct_commit_rejected
                CommitTextResult.UNKNOWN -> R.string.direct_commit_uncertain
                else -> return
            }
        }
        toast = Toast.makeText(context, message, Toast.LENGTH_LONG).also { it.show() }
    }

    fun clear() { toast?.cancel(); toast = null }
}
