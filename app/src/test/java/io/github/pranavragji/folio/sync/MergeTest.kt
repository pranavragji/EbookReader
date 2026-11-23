package io.github.pranavragji.folio.sync

import io.github.pranavragji.folio.data.Book
import io.github.pranavragji.folio.data.SavedTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {
    private fun book(id: String, p: Float, at: Long) = Book(id, "file:///$id", id, format = "epub", isComic = false, progress = p, progressUpdatedAt = at)

    @Test fun lastWriterWins() {
        val remote = SyncFile(
            books = mapOf(
                "a" to BookSync(0.9f, "cfi-a", 200),   // remote newer → applied locally
                "b" to BookSync(0.1f, "cfi-b", 100),   // local newer → uploaded
                "z" to BookSync(0.5f, null, 50),       // unknown here → kept for other devices
            ),
            titles = mapOf("md|1" to TitleSync("md", "1", "One", lastPage = 7, updatedAt = 300)),
        )
        val m = merge(
            remote,
            listOf(book("a", 0.2f, 150), book("b", 0.6f, 180), book("c", 0f, 0)),
            listOf(SavedTitle("md", "2", "Two", updatedAt = 10)),
        )
        assertEquals(0.9f, m.books["a"]!!.progress)
        assertTrue("b" !in m.books)
        assertEquals(0.6f, m.file.books["b"]!!.progress)
        assertTrue("z" in m.file.books)
        assertTrue("c" !in m.file.books)          // never-opened books don't pollute the file
        assertEquals(listOf("1"), m.titles.map { it.titleId })
        assertEquals(setOf("md|1", "md|2"), m.file.titles.keys)
    }
}
