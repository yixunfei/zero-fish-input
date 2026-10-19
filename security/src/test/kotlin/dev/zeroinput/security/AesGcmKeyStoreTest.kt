package dev.zeroinput.security

import java.security.KeyStoreException
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmKeyStoreTest {
    @Test fun unavailableProviderIsNotReportedAsAnAbsentKey() {
        // JVM unit tests intentionally have no AndroidKeyStore provider.
        assertThrows(KeyStoreException::class.java) { AesGcmKeyStore("public-fixture").hasKey() }
    }
}
