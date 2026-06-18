// SPDX-License-Identifier: GPL-3.0-only
package org.meshtastic.kzstd

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Native counterpart to the JVM `ConcurrencyTest`: the zero-`atomicfu` thread-safety
 * guarantee rests on Kotlin/Native's memory model (immutable shared state + safely
 * published `by lazy` tables), which differs from the JVM's. This shares ONE
 * [ZstdDictionary] across several native [Worker] threads doing concurrent
 * compress + decompress and asserts every round-trip is correct. Runs on every
 * native target (executed on macOS/iOS-sim hosts; cross-compiled elsewhere).
 *
 * Uses the `Worker` API (marked obsolete but still the simplest dependency-free
 * native threading primitive — kzstd has no kotlinx-coroutines dependency).
 */
@OptIn(ObsoleteWorkersApi::class)
class NativeConcurrencyTest {

    @Test
    fun sharedDictionaryAcrossNativeWorkers() {
        val dict = ZstdDictionary(TestVectors.trainedDict)
        val workers = List(4) { Worker.start() }
        try {
            val futures = workers.mapIndexed { idx, w ->
                // The job lambda must be non-capturing: the dictionary + index arrive via
                // the producer, and MAX_DECOMPRESSED_SIZE is a compile-time const (not a
                // capture). ZstdDictionary is immutable, so sharing it across workers is safe.
                w.execute(TransferMode.SAFE, { Pair(dict, idx) }) { (d, i) ->
                    var ok = true
                    repeat(25) { r ->
                        val s = TestVectors.structured[(i + r) % TestVectors.structured.size]
                        val back = Zstd.decompress(Zstd.compress(s, d), d, TestVectors.MAX_DECOMPRESSED_SIZE)
                        if (!s.contentEquals(back)) ok = false
                    }
                    ok
                }
            }
            assertTrue(
                futures.all { it.result },
                "concurrent native workers sharing one ZstdDictionary produced a mismatch",
            )
        } finally {
            workers.forEach { it.requestTermination().result }
        }
    }
}
