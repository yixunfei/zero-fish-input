package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.EngineUpdate
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.PageDirection
import java.util.TreeMap

/** Bounded browsing history; selection restores and verifies the engine's original page. */
internal class CandidateWindow {
    data class Route(val page: Int, val index: Int, val id: String, val text: String)
    private val pages = TreeMap<Int, EngineSnapshot>()
    private var cursor = 0
    private var routes = emptyMap<String, Route>()
    val isBrowsing: Boolean get() = pages.isNotEmpty()

    fun clear() { pages.clear(); routes = emptyMap(); cursor = 0 }

    fun changePage(engine: InputEngine, current: EngineSnapshot, direction: PageDirection): EngineUpdate {
        if (pages.isEmpty()) pages[cursor] = current
        val edge = (if (direction == PageDirection.NEXT) pages.lastEntry() else pages.firstEntry())
            ?: return EngineUpdate(current, consumed = false)
        val allowed = if (direction == PageDirection.NEXT) edge.value.hasNextPage else edge.value.hasPreviousPage
        if (!allowed) return EngineUpdate(current, consumed = false)
        val target = edge.key + if (direction == PageDirection.NEXT) 1 else -1
        val update = moveTo(engine, target, current)
        if (!update.consumed && pages.containsKey(cursor)) pages[cursor] = update.snapshot
        if (cursor == target && update.snapshot.rawInput == current.rawInput && update.snapshot.composition == current.composition) {
            pages[target] = update.snapshot
            while (pages.size > MAX_PAGES) {
                if (direction == PageDirection.NEXT) pages.pollFirstEntry() else pages.pollLastEntry()
            }
        } else if (update.snapshot.rawInput != current.rawInput || update.snapshot.composition != current.composition) clear()
        return update
    }

    fun snapshot(current: EngineSnapshot): EngineSnapshot {
        if (pages.isEmpty()) {
            routes = current.candidates.mapIndexed { index, candidate ->
                candidate.id to Route(cursor, index, candidate.id, candidate.text)
            }.toMap()
            return current
        }
        val visible = ArrayList<Candidate>()
        val updatedRoutes = LinkedHashMap<String, Route>()
        val seen = HashSet<String>()
        for ((page, snapshot) in pages) for ((index, candidate) in snapshot.candidates.withIndex()) {
            if (!seen.add(candidate.text)) continue
            // Preserve initial-page identities when browsing appends rows.
            val id = if (page == 0) candidate.id else "page:$page:${candidate.id}"
            visible += candidate.copy(id = id)
            updatedRoutes[id] = Route(page, index, candidate.id, candidate.text)
        }
        routes = updatedRoutes
        return current.copy(candidates = visible, highlightedIndex = 0,
            hasPreviousPage = pages.firstEntry()?.value?.hasPreviousPage == true,
            hasNextPage = pages.lastEntry()?.value?.hasNextPage == true)
    }

    fun route(id: String): Route? = routes[id]

    fun select(engine: InputEngine, route: Route, current: EngineSnapshot): EngineUpdate {
        val moved = moveTo(engine, route.page, current)
        val candidate = moved.snapshot.candidates.getOrNull(route.index)
        if (cursor != route.page || candidate?.id != route.id || candidate.text != route.text) {
            return moved.copy(consumed = false)
        }
        clear()
        return engine.selectCandidate(route.index)
    }

    private fun moveTo(engine: InputEngine, target: Int, current: EngineSnapshot): EngineUpdate {
        var update = EngineUpdate(current, consumed = false)
        if (kotlin.math.abs(target - cursor) > MAX_PAGES) return update
        while (cursor != target) {
            val direction = if (target > cursor) PageDirection.NEXT else PageDirection.PREVIOUS
            update = engine.changePage(direction)
            if (!update.consumed || update.committedText.isNotEmpty()) return update
            cursor += if (direction == PageDirection.NEXT) 1 else -1
        }
        return update
    }

    companion object { const val MAX_PAGES = 6 }
}
