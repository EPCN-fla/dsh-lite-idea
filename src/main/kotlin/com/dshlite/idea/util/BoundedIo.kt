package com.dshlite.idea.util

import java.io.IOException
import java.io.InputStream

/** Read at most [maxBytes], closing the stream so an overflowing writer is unblocked. */
internal fun readBytesBounded(input: InputStream, maxBytes: Int): ByteArray {
    require(maxBytes >= 0 && maxBytes < Int.MAX_VALUE)
    return input.use {
        val bytes = it.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes) throw IOException("process output exceeds $maxBytes bytes")
        bytes
    }
}

internal fun readUtf8Bounded(input: InputStream, maxBytes: Int): String =
    readBytesBounded(input, maxBytes).toString(Charsets.UTF_8)
