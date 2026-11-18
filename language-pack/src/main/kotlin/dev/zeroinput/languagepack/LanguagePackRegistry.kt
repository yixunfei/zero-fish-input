package dev.zeroinput.languagepack

import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.EngineDescriptor

/** Keeps enabled, data-only language pack engines in sync with disk. */
class LanguagePackRegistry(
    private val installer: LanguagePackInstaller,
) : AutoCloseable {
    @Volatile
    private var factories: Map<String, LanguagePackEngineFactory> = emptyMap()

    @Synchronized
    fun refresh() {
        val refreshed = buildMap {
            installer.refreshInstalled().filter(InstalledLanguagePack::enabled).forEach { pack ->
                recoverable { LanguagePackEngineFactory(pack) }
                    .getOrNull()
                    ?.takeIf { recoverable { it.isAvailable() }.getOrDefault(false) }
                    ?.let { put(pack.key, it) }
            }
        }
        factories = refreshed
    }

    fun contains(packKey: String?): Boolean = packKey != null && factories.containsKey(packKey)

    fun create(packKey: String?): InputEngine? = packKey?.let { key ->
        recoverable { factories[key]?.create() }.getOrNull()
    }

    fun descriptors(): List<EngineDescriptor> = factories.values
        .map(LanguagePackEngineFactory::descriptor)
        .sortedBy(EngineDescriptor::id)

    fun installed(): List<InstalledLanguagePack> = installer.listInstalled()

    fun available(): List<InstalledLanguagePack> = installer.enabled().filter { contains(it.key) }

    override fun close() {
        factories = emptyMap()
    }
}
