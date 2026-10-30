package dev.zeroinput.ime.input

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StyledDeletionTest {
    @Test fun deletesCompleteReplacementAtCursorButNotOrdinaryBracketedText() {
        val text = SpannableString("a[微笑]")
        text.setSpan(icon(), 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertEquals(4, UnicodeDeletionBoundary.precedingLength(text))
        assertEquals(1, UnicodeDeletionBoundary.precedingLength("a[微笑]"))
    }

    @Test fun doesNotExpandFormattingOrReplacementThatMayBeClipped() {
        val text = SpannableString("[微笑]")
        text.setSpan(icon(), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertEquals(1, UnicodeDeletionBoundary.precedingLength(text))
        val styled = SpannableString("a[微笑]")
        styled.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 1, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertEquals(1, UnicodeDeletionBoundary.precedingLength(styled))
    }

    private fun icon() = object : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?) = 20
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int,
            y: Int, bottom: Int, paint: Paint) = Unit
    }
}
