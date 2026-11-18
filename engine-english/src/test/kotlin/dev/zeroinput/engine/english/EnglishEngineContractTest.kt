package dev.zeroinput.engine.english

import dev.zeroinput.engine.api.*
import org.junit.Test

class EnglishEngineContractTest {
    @Test fun sharedContract() = InputEngineContract.verify(EnglishInputEngine(), InputLanguage.ENGLISH, "hello", "hello", "hello ")
}
