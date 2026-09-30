package dev.zeroinput.ime.input

/** Tracks acknowledgements of our own edits; unexpected cursor updates revoke the anchor. */
internal class EditorSelectionState(start: Int, end: Int) {
    data class Selection(val start: Int, val end: Int, val composingStart: Int = -1, val composingEnd: Int = -1)
    data class CommitRange(val start: Int, val end: Int)

    private var predicted = Selection(start, end)
    private var observed = predicted
    private var composingStart: Int? = null
    private val pending = ArrayDeque<Selection>()
    private val acknowledged = ArrayDeque<Selection>()
    private val discardedCompositions = ArrayDeque<DiscardedComposition>()
    private val unanchored = ArrayDeque<Pair<Int, Boolean>>()
    private var committed: CommitRange? = null

    fun replaced(length: Int, composing: Boolean) {
        committed = null
        if (predicted.start < 0 || predicted.end < 0) {
            if (unanchored.size == 32) unanchored.removeFirst()
            unanchored.addLast(length to composing)
            return
        }
        val start = composingStart ?: minOf(predicted.start, predicted.end)
        predicted = Selection(start + length, start + length,
            if (composing && length > 0) start else -1, if (composing && length > 0) start + length else -1)
        if (pending.size == 32) recordDiscarded(pending.removeFirst())
        pending.addLast(predicted)
        composingStart = start.takeIf { composing && length > 0 }
        if (!composing && length > 0) committed = CommitRange(start, start + length)
    }

    fun updated(start: Int, end: Int, composingStart: Int = -1, composingEnd: Int = -1,
        previousStart: Int = -1, previousEnd: Int = -1): Boolean {
        val previous = observed
        observed = Selection(start, end, composingStart, composingEnd)
        if (unanchored.isNotEmpty() && anchorUnknownSelection()) return false
        // Android coalesces updates. The composing range distinguishes a final
        // commit from an earlier preedit with the same cursor position.
        val expected = pending.lastIndexOf(observed)
        if (expected >= 0) {
            repeat(expected + 1) { remember(pending.removeFirst()) }
            return false
        }
        if (observed in acknowledged &&
            (previousStart != predicted.start || previousEnd != predicted.end)) {
            observed = previous
            return false
        }
        if (isDelayedComposingAcknowledgement(previousStart, previousEnd)) {
            observed = previous
            return false
        }
        if (observed == predicted) return false
        predicted = observed
        this.composingStart = null
        pending.clear()
        acknowledged.clear()
        discardedCompositions.clear()
        committed = null
        return true
    }

    fun verifiedRange(length: Int): CommitRange? = committed?.takeIf {
        pending.isEmpty() && observed == Selection(it.end, it.end) && it.end - it.start == length
    }

    fun selectedLength(): Int? = observed.takeIf {
        pending.isEmpty() && it == predicted && it.start >= 0 && it.end >= 0 && it.start != it.end
    }?.let { kotlin.math.abs(it.end - it.start) }

    fun reopened(range: CommitRange) {
        committed = null
        predicted = Selection(range.end, range.end, range.start, range.end)
        if (pending.size == 32) recordDiscarded(pending.removeFirst())
        pending.addLast(predicted)
        composingStart = range.start
    }

    /** Register the no-span selection before Android can synchronously acknowledge it. */
    fun finishComposition() {
        if (predicted.start < 0 || predicted.end < 0) {
            unanchored.lastOrNull()?.let { (length, _) ->
                if (unanchored.size == 32) unanchored.removeFirst()
                unanchored.addLast(length to false)
            }
            composingStart = null
            return
        }
        if (predicted.composingStart < 0 && predicted.composingEnd < 0 && composingStart == null) {
            composingStart = null
            return
        }
        predicted = Selection(predicted.start, predicted.end)
        if (pending.size == 32) recordDiscarded(pending.removeFirst())
        pending.addLast(predicted)
        composingStart = null
    }
    fun invalidate() { committed = null }
    fun unknown() {
        committed = null
        composingStart = null
        pending.clear()
        acknowledged.clear()
        discardedCompositions.clear()
        unanchored.clear()
        predicted = Selection(-1, -1)
        observed = predicted
    }

    private fun anchorUnknownSelection(): Boolean {
        if (observed.start < 0 || observed.start != observed.end) return false
        val expectations = unanchored.toList()
        if (observed.composingStart < 0 && observed.composingEnd < 0 &&
            expectations.lastOrNull()?.second == false) {
            // A final cursor alone cannot prove where an unknown edit began.
            // Keep normal typing anchored, but do not authorize reconversion.
            predicted = observed
            composingStart = null
            unanchored.clear()
            return true
        }
        val match = expectations.indexOfLast { (length, composing) ->
            composing && observed.composingStart >= 0 &&
                observed.composingEnd - observed.composingStart == length &&
                observed.start == observed.composingEnd
        }
        if (match < 0) return false
        val origin = observed.composingStart
        pending.clear()
        expectations.take(match + 1).filter { it.second && it.first > 0 }.forEach { (length, _) ->
            remember(Selection(origin + length, origin + length, origin, origin + length))
        }
        var cursor = observed.start
        var activeComposition = true
        expectations.drop(match + 1).forEach { (length, composing) ->
            val start = if (activeComposition) origin else cursor
            cursor = start + length
            pending.addLast(Selection(cursor, cursor,
                if (composing && length > 0) start else -1,
                if (composing && length > 0) cursor else -1))
            activeComposition = composing && length > 0
        }
        val (latestLength, latestComposing) = expectations.last()
        predicted = pending.lastOrNull() ?: observed
        this.composingStart = predicted.composingStart.takeIf { latestComposing && latestLength > 0 }
        if (!latestComposing && latestLength > 0) {
            committed = CommitRange(predicted.start - latestLength, predicted.start)
        }
        unanchored.clear()
        return true
    }

    private fun remember(selection: Selection) {
        if (acknowledged.size == 32) acknowledged.removeFirst()
        acknowledged.addLast(selection)
    }

    private fun isDelayedComposingAcknowledgement(previousStart: Int, previousEnd: Int): Boolean {
        return previousStart >= 0 && previousEnd >= 0 &&
            (previousStart != predicted.start || previousEnd != predicted.end) &&
            observed.composingEnd == observed.start && observed.start == observed.end &&
            discardedCompositions.any { discarded ->
                observed.composingStart == discarded.origin && observed.start in discarded.firstEnd..discarded.lastEnd
            }
    }

    private fun recordDiscarded(selection: Selection) {
        if (selection.composingStart < 0 || selection.composingEnd != selection.start ||
            selection.start != selection.end) return
        val last = discardedCompositions.lastOrNull()
        if (last?.origin == selection.composingStart) {
            discardedCompositions.removeLast()
            discardedCompositions.addLast(last.copy(firstEnd = minOf(last.firstEnd, selection.start),
                lastEnd = maxOf(last.lastEnd, selection.start)))
        } else {
            if (discardedCompositions.size == 32) discardedCompositions.removeFirst()
            discardedCompositions.addLast(DiscardedComposition(selection.composingStart, selection.start, selection.start))
        }
    }

    private data class DiscardedComposition(val origin: Int, val firstEnd: Int, val lastEnd: Int)
}
