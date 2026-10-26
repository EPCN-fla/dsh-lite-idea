package com.dshlite.idea.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class BoundedIoTest {
    @Test fun `bounded reader returns complete output and rejects overflow`() {
        assertEquals("hello", readUtf8Bounded(ByteArrayInputStream("hello".toByteArray()), 5))
        assertThrows(IOException::class.java) {
            readUtf8Bounded(ByteArrayInputStream(ByteArray(6)), 5)
        }
    }
}
