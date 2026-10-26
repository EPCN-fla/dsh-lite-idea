package com.dshlite.idea

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Properties

class DshBundleTest {
    @Test fun `localized bundles have identical keys and message placeholders`() {
        val english = load("DshBundle.properties")
        val chinese = load("DshBundle_zh_CN.properties")
        assertEquals(english.stringPropertyNames(), chinese.stringPropertyNames())
        val placeholders = Regex("\\{\\d+}")
        for (key in english.stringPropertyNames()) {
            assertEquals(
                key,
                placeholders.findAll(english.getProperty(key)).map { it.value }.toSet(),
                placeholders.findAll(chinese.getProperty(key)).map { it.value }.toSet(),
            )
        }
    }

    private fun load(name: String): Properties = Properties().apply {
        DshBundleTest::class.java.getResourceAsStream("/messages/$name")!!.reader(Charsets.UTF_8).use { load(it) }
    }
}
