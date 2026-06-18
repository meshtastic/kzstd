// SPDX-License-Identifier: GPL-3.0-only
package org.meshtastic.kzstd

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression-protects the immutability / thread-safety guarantee that lets kzstd
 * drop the atomicfu lock: ONE [ZstdDictionary] shared across many threads doing
 * concurrent compress + decompress must stay correct, because the digested state
 * is built once in the constructor and never mutated thereafter.
 */
class ConcurrencyTest {

    @Test
    fun sharedDictionaryIsThreadSafeUnderConcurrentUse() {
        val dict = ZstdDictionary(TestVectors.trainedDict)
        val max = TestVectors.MAX_DECOMPRESSED_SIZE
        val samples = TestVectors.corpus
        val firstError = AtomicReference<Throwable?>(null)

        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(64) { t ->
                pool.submit {
                    try {
                        repeat(50) { r ->
                            val s = samples[(t + r) % samples.size]
                            val back = Zstd.decompress(Zstd.compress(s, dict), dict, max)
                            if (!s.contentEquals(back)) {
                                firstError.compareAndSet(null, AssertionError("mismatch at size=${s.size}"))
                            }
                        }
                    } catch (e: Throwable) {
                        firstError.compareAndSet(null, e)
                    }
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "worker threads did not finish in time")
        } finally {
            pool.shutdownNow()
        }
        assertNull(firstError.get(), "concurrent use of a shared ZstdDictionary failed: ${firstError.get()}")
    }
}
