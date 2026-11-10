package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiWorkspaceContext
import org.json.JSONArray
import org.json.JSONObject

/** User-selected text only. Local identities and grants never enter the payload. */
internal object AiWorkspaceJson {
    fun append(target: JSONObject, workspace: AiWorkspaceContext): JSONObject = target.apply {
        workspace.persona?.let { put("user_preferences", JSONObject().put("name", it.name)
            .put("description", it.description).put("instructions", it.instructions)) }
        if (workspace.memories.isNotEmpty()) put("selected_memories", JSONArray(workspace.memories.map { it.text }))
        if (workspace.skills.isNotEmpty()) put("selected_skills", JSONArray(workspace.skills.map {
            JSONObject().put("name", it.name).put("description", it.description).put("instructions", it.instructions)
        }))
    }
}
