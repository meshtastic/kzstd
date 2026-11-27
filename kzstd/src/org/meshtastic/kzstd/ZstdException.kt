// SPDX-License-Identifier: GPL-3.0-or-later
package org.meshtastic.kzstd

/**
 * Thrown when a zstd frame cannot be compressed or decompressed — a malformed or
 * truncated frame, an unsupported feature, a corrupt dictionary, or output that
 * would exceed the caller's `maxSize` cap.
 *
 * This is the single public error type for the codec — `final`, because the codec
 * exposes exactly one error type. [Zstd] and [ZstdDictionary] let it propagate from
 * the encoder/decoder/parser and wrap any other unexpected failure in it, so a
 * caller can catch exactly one exception type on every target.
 */
public class ZstdException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
