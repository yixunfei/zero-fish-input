package dev.zeroinput.languagepack

import org.json.JSONObject

internal object LanguagePackParser {
    fun parse(json: String): LanguagePackManifest {
        // A number of desktop archive tools emit UTF-8 manifests with a BOM.
        // Android's UTF-8 reader preserves that marker, while JSONObject
        // expects the first character to be `{`; normalize it at this single
        // trust boundary so installed and imported manifests behave alike.
        val normalized = json.removePrefix("\uFEFF")
        require(normalized.length <= MAX_MANIFEST_CHARS) { "Language pack manifest is too large" }
        val root = LanguagePackJson.parse(normalized) as? JSONObject
            ?: throw IllegalArgumentException("Language pack manifest must be an object")
        val format = root.requiredInteger("formatVersion")
        require(format == SUPPORTED_FORMAT.toLong()) { "Unsupported language pack format" }
        val fileArray = root.getJSONArray("files")
        require(fileArray.length() in 1..MAX_FILES) { "Invalid language pack file count" }

        val files = List(fileArray.length()) { index ->
            val item = fileArray.getJSONObject(index)
            val path = PackPathPolicy.validate(item.requiredString("path"))
            require(path != MANIFEST_PATH) { "The manifest cannot be declared as a payload" }
            val sha256 = item.requiredString("sha256").lowercase()
            require(SHA_256.matches(sha256)) { "Invalid SHA-256 for $path" }
            val size = item.requiredInteger("size")
            require(size in 0..MAX_SINGLE_FILE_BYTES) { "Invalid size for $path" }
            LanguagePackFile(path, sha256, size)
        }
        require(files.distinctBy(LanguagePackFile::path).size == files.size) {
            "Language pack contains duplicate paths"
        }
        require(files.sumOf(LanguagePackFile::size) <= MAX_TOTAL_FILE_BYTES) {
            "Language pack expands beyond the allowed size"
        }

        return LanguagePackManifest(
            formatVersion = format.toInt(),
            id = validateIdentifier(root.requiredString("id"), "id"),
            displayName = root.requiredString("displayName").trim().also {
                require(it.isNotEmpty() && it.length <= 80) { "Invalid display name" }
            },
            languageTag = validateLanguageTag(root.requiredString("languageTag")),
            version = validateIdentifier(root.requiredString("version"), "version"),
            engineId = validateIdentifier(root.requiredString("engineId"), "engine id"),
            files = files,
        )
    }

    private fun JSONObject.requiredString(name: String): String = opt(name) as? String
        ?: throw IllegalArgumentException("Invalid language pack string field")

    private fun JSONObject.requiredInteger(name: String): Long = when (val value = opt(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("Invalid language pack integer field")
    }

    private fun validateIdentifier(value: String, field: String): String = value.trim().also {
        require(IDENTIFIER.matches(it)) { "Invalid language pack $field" }
    }

    private fun validateLanguageTag(value: String): String = value.trim().also {
        require(LANGUAGE_TAG.matches(it)) { "Invalid language tag" }
        require(LanguagePackLanguage.fromTag(it) != null) {
            "Unsupported language tag: $it"
        }
    }

    private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val LANGUAGE_TAG = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")
    private val SHA_256 = Regex("[a-f0-9]{64}")
    private const val MANIFEST_PATH = "manifest.json"
    private const val SUPPORTED_FORMAT = 1
    private const val MAX_MANIFEST_CHARS = 256 * 1024
    private const val MAX_FILES = 2_048
    private const val MAX_SINGLE_FILE_BYTES = 32L * 1024 * 1024
    private const val MAX_TOTAL_FILE_BYTES = 256L * 1024 * 1024
}
