package dev.zeroinput.ai.api

import org.junit.Assert.*
import org.junit.Test

class AiContextLibraryTest {
    private val role = AiPersona("one", "Public role", "", "Be concise")

    @Test fun memoryCannotCrossRolesUnlessExplicitlyShared() {
        val private = AiMemory("private", role.id, "Public fixture")
        val shared = AiMemory("shared", null, "Public shared fixture")
        val library = AiContextLibrary(personas = listOf(role), memories = listOf(private, shared))
        assertThrows(IllegalArgumentException::class.java) { library.select(null, setOf(private.id)) }
        assertEquals(listOf(shared), library.select(null, setOf(shared.id)).memories)
        assertEquals(listOf(private), library.select(role.id, setOf(private.id)).memories)
        assertThrows(IllegalArgumentException::class.java) { library.select(role.id, setOf("missing")) }
    }

    @Test fun allSelectedSourcesShareTheContextBudget() {
        val workspace = AiWorkspaceContext(role)
        val history = listOf(AiMessage(AiRole.USER, "x".repeat(AiReference.MAX_CONTEXT_CHARS)))
        assertThrows(IllegalArgumentException::class.java) { AiRequest(null, AiAction.ASK, "question", history = history, workspaceContext = workspace) }
        assertEquals(workspace.characterCount, AiContextState(workspace = workspace).characterCount)
    }

    @Test fun snapshotsCopyListsAndKeepTextOutOfDiagnostics() {
        val roles = mutableListOf(role)
        val library = AiContextLibrary(personas = roles)
        roles.clear()
        assertEquals(1, library.personas.size)
        assertThrows(UnsupportedOperationException::class.java) { (library.personas as MutableList<AiPersona>).clear() }
        assertFalse(library.toString().contains(role.name))
        assertFalse(role.toString().contains(role.instructions))
        assertFalse(AiWorkspaceContext(role).toString().contains(role.instructions))
    }

    @Test fun malformedOrOrphanedDataIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AiPersona("id", "", "", "instructions") }
        assertThrows(IllegalArgumentException::class.java) { AiMemory("id", null, "\u0000") }
        assertThrows(IllegalArgumentException::class.java) { AiContextLibrary(memories = listOf(AiMemory("id", "missing", "fixture"))) }
        assertThrows(IllegalArgumentException::class.java) { AiContextLibrary(personas = listOf(role, role)) }
    }
}
