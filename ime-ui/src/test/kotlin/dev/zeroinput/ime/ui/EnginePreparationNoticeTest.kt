package dev.zeroinput.ime.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePreparationNoticeTest {
    @Test fun `repeated keys and renders only notify once until a new editor or retry`() {
        val notice = EnginePreparationNotice()
        assertFalse(notice.onInputAttempt())
        notice.update(InputEngineStatus.PREPARING)
        assertTrue(notice.onInputAttempt())
        repeat(10) {
            notice.update(InputEngineStatus.PREPARING)
            assertFalse(notice.onInputAttempt())
        }
        notice.resetEditor()
        assertTrue(notice.onInputAttempt())
        notice.update(InputEngineStatus.FAILED)
        assertFalse(notice.onInputAttempt())
        notice.update(InputEngineStatus.PREPARING)
        assertTrue(notice.onInputAttempt())
    }

    @Test fun `ready hidden and pending configuration never show a preparation reminder`() {
        for (status in listOf(InputEngineStatus.READY, InputEngineStatus.HIDDEN, InputEngineStatus.PENDING_CONFIGURATION)) {
            val notice = EnginePreparationNotice()
            notice.update(InputEngineStatus.PREPARING)
            notice.update(status)
            assertFalse(notice.onInputAttempt())
        }
    }
}
