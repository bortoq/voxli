package com.voxli.flibusta.provider

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FlibustaProviderTest {

    private lateinit var provider: FlibustaProvider

    @Before
    fun setUp() {
        provider = FlibustaProvider(OkHttpClient.Builder().build())
    }

    // ── OPDS parsing ─────────────────────────────────────────────

    @Test
    fun parseOpdsEntry_parsesMultipleEntries() {
        val xml = loadFixture("flibusta/opds_sample.xml")
        val entries = provider.parseOpdsEntry(xml)
        assertEquals(3, entries.size)
    }

    @Test
    fun parseOpdsEntry_extractsAllFields() {
        val xml = loadFixture("flibusta/opds_sample.xml")
        val entries = provider.parseOpdsEntry(xml)

        val book = entries.find { it.id == 100500L }
        assertNotNull("Entry 100500 not found", book)
        assertEquals("Война и мир", book!!.title)
        assertEquals("Лев Толстой", book.author)
        assertEquals("Проза", book.genre)
        assertEquals("Роман-эпопея о жизни русского общества.", book.annotation)
        assertTrue("Should have fb2", book.hasFb2)
        assertTrue("Should have epub", book.hasEpub)
        assertEquals("2025-11-15T12:00:00Z", book.createdAt)
        assertEquals("2025-11-15T12:30:00Z", book.updatedAt)
    }

    @Test
    fun parseOpdsEntry_entryWithOnlyFb2() {
        val xml = loadFixture("flibusta/opds_sample.xml")
        val entries = provider.parseOpdsEntry(xml)
        val crimeAndPunishment = entries.find { it.id == 100501L }
        assertNotNull(crimeAndPunishment)
        val cp = crimeAndPunishment!!
        assertEquals("Преступление и наказание", cp.title)
        assertEquals("Фёдор Достоевский", cp.author)
        assertEquals("Детектив", cp.genre)
        assertTrue("Should have fb2", cp.hasFb2)
        assertFalse("Should NOT have epub", cp.hasEpub)
    }

    @Test
    fun parseOpdsEntry_entryWithOnlyEpub() {
        val xml = loadFixture("flibusta/opds_sample.xml")
        val entries = provider.parseOpdsEntry(xml)
        val anna = entries.find { it.id == 100502L }
        assertNotNull(anna)
        val a = anna!!
        assertEquals("Анна Каренина", a.title)
        assertEquals("Лев Толстой", a.author)
        assertEquals("Проза", a.genre)
        assertFalse("Should NOT have fb2", a.hasFb2)
        assertTrue("Should have epub", a.hasEpub)
    }

    @Test
    fun parseOpdsEntry_emptyXml_returnsEmpty() {
        assertTrue(provider.parseOpdsEntry("").isEmpty())
        assertTrue(provider.parseOpdsEntry("   ").isEmpty())
    }

    @Test
    fun parseOpdsEntry_noEntries_returnsEmpty() {
        val xml = """<?xml version="1.0"?><feed><title>Empty</title></feed>"""
        assertTrue(provider.parseOpdsEntry(xml).isEmpty())
    }

    @Test
    fun parseOpdsEntry_malformedXml_returnsEmptyGracefully() {
        val xml = """<feed><entry><id>nope</id><title>Broken"""
        val entries = provider.parseOpdsEntry(xml)
        // Should not crash, empty if invalid
        assertTrue(entries.isEmpty())
    }

    @Test
    fun parseOpdsEntry_entryWithoutId_skipped() {
        val xml = """
            <?xml version="1.0"?>
            <feed>
                <entry>
                    <title>No ID</title>
                    <author><name>Author</name></author>
                </entry>
                <entry>
                    <id>urn:flibusta:42</id>
                    <title>Has ID</title>
                </entry>
            </feed>
        """.trimIndent()
        val entries = provider.parseOpdsEntry(xml)
        assertEquals(1, entries.size)
        assertEquals(42L, entries[0].id)
        assertEquals("Has ID", entries[0].title)
    }

    @Test
    fun parseOpdsEntry_entryWithoutTitle_skipped() {
        val xml = """
            <?xml version="1.0"?>
            <feed>
                <entry>
                    <id>urn:flibusta:1</id>
                    <author><name>Author</name></author>
                </entry>
            </feed>
        """.trimIndent()
        assertTrue(provider.parseOpdsEntry(xml).isEmpty())
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun loadFixture(path: String): String {
        val resource = javaClass.classLoader?.getResource(path)
            ?: throw IllegalStateException("Fixture not found: $path")
        return resource.readText()
    }
}
