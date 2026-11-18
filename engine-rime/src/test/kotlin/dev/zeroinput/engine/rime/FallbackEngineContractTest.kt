package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.*
import org.junit.Test

class FallbackEngineContractTest {
    @Test fun sharedContract() = InputEngineContract.verify(FallbackPinyinEngine(), InputLanguage.CHINESE, "ni", "你")
}
