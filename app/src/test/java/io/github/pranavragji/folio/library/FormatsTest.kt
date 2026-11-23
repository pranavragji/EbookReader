package io.github.pranavragji.folio.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class FormatsTest {
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun partialMd5MatchesKoreaderSampling() {
        // < 1 KiB: only the offset-0 sample exists, so it's the plain MD5.
        assertEquals("5d41402abc4b2a76b9719d911017c592", partialMd5("hello".byteInputStream()))
        // 3000 bytes: samples at 0 and 1024 (1 KiB each); 4096 is past EOF.
        val data = ByteArray(3000) { (it * 31).toByte() }
        assertEquals(md5(data.copyOfRange(0, 2048)), partialMd5(data.inputStream()))
    }

    @Test fun naturalSortAndFormats() {
        assertEquals(listOf("p1.jpg", "p2.jpg", "p10.jpg"), listOf("p10.jpg", "p2.jpg", "p1.jpg").sortedWith(naturalOrder))
        assertEquals("fbz", Formats.of("Tolstoy.fb2.zip"))
        assertEquals("cbz", Formats.of("Vol 01.ZIP"))
        assertEquals(null, Formats.of(".hidden.epub"))
    }
}
