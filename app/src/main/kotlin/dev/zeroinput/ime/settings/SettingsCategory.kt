package dev.zeroinput.ime.settings

import dev.zeroinput.ime.R

/** Stable navigation identifiers, independent of translated labels. */
enum class SettingsCategory(val title: Int, val summary: Int) {
    HOME(R.string.app_name, R.string.settings_general_summary),
    INPUT(R.string.section_input, R.string.settings_input_summary),
    DICTIONARIES(R.string.settings_dictionaries, R.string.settings_dictionaries_summary),
    PRIVACY(R.string.settings_privacy, R.string.settings_privacy_summary),
    AI(R.string.ai_title, R.string.settings_ai_summary),
    GENERAL(R.string.settings_general, R.string.settings_general_summary),
}
