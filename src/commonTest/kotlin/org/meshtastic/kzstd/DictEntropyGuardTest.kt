// SPDX-License-Identifier: GPL-3.0-only
package org.meshtastic.kzstd

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Guards the dictionary-entropy code path against silently going dark.
 *
 * If the committed test dictionary were NOT a genuinely trained dict (magic
 * 0xEC30A437), `ParsedDictionary.parse` would treat it as raw content (null
 * Huffman + null FSE tables), and the dict-entropy decode branches (treeless
 * literals, FSE-repeat, repeat-offset seeding) would never run — while every
 * other test stayed green. This fails loudly in that case. The branches are
 * actually *executed* by the libzstd-with-trained-dict → kzstd direction of
 * `KzstdLibzstdInteropTest` (jvm), since kzstd's own encoder never emits them.
 */
class DictEntropyGuardTest {

    @Test
    fun committedTestDictIsGenuinelyTrained() {
        val dict = TestVectors.trainedDict
        assertTrue(dict.size >= 8, "dict too small to carry a header")
        assertContentEquals(
            TestVectors.TRAINED_DICT_MAGIC,
            dict.copyOfRange(0, 4),
            "committed test dict must be a TRAINED dict (magic 37 A4 30 EC), not raw content",
        )
    }
}
