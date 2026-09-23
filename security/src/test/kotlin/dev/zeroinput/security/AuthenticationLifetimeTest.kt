package dev.zeroinput.security

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AuthenticationLifetimeTest {
    @Test fun validAuthorizationCanBeConsumedOnlyOnce() {
        val grant = AuthenticationLifetime(100)
        assertTrue(grant.consume(100))
        assertFalse(grant.consume(100))
    }

    @Test fun expiryIsThirtySecondsEvenWhenACallerRequestsALongerWindow() {
        assertTrue(AuthenticationLifetime(100).consume(30_100))
        assertFalse(AuthenticationLifetime(100).consume(30_101))
        assertFalse(AuthenticationLifetime(100).consume(30_101, Long.MAX_VALUE))
        assertFalse(AuthenticationLifetime(100).consume(201, 100))
    }

    @Test fun expiredOrReversedClocksCannotBeRetriedWithAFreshTime() {
        for (now in listOf(99L, 30_101L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val grant = AuthenticationLifetime(100)
            assertFalse(grant.consume(now))
            assertFalse(grant.consume(100))
        }
        assertFalse(AuthenticationLifetime(-1).consume(0))
    }

    @Test fun invalidRequestedLifetimeConsumesTheAuthorization() {
        val grant = AuthenticationLifetime(100)
        assertFalse(grant.consume(100, -1))
        assertFalse(grant.consume(100))
    }

    @Test fun concurrentConsumersHaveExactlyOneWinner() {
        val grant = AuthenticationLifetime(100)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { workers.submit<Boolean> {
                ready.countDown(); check(start.await(3, TimeUnit.SECONDS)); grant.consume(101)
            } }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, results.count { it.get(3, TimeUnit.SECONDS) })
        } finally { start.countDown(); workers.shutdownNow() }
    }
}
