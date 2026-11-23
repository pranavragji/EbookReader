package io.github.pranavragji.folio.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A local file. [id] is a KOReader-style partial MD5 of the file, so the same book has the
 * same id on every device. That is what makes progress sync work without server-side ids.
 */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String,
    val uri: String,                 // content:// (SAF) or file:// (app downloads)
    val title: String,
    val author: String = "",
    val format: String,              // epub mobi azw azw3 fb2 fbz pdf cbz cbr cb7 cbt txt html ...
    val isComic: Boolean,
    val coverPath: String? = null,   // absolute path to a cached cover image
    val size: Long = 0,
    val folder: String = "",         // library root it was found under
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0,
    val progress: Float = 0f,        // 0..1
    val locator: String? = null,     // format-specific: EPUB CFI, or page index for pdf/comics
    val progressUpdatedAt: Long = 0, // drives sync last-writer-wins
)

/** An online title (MangaDex, OPDS...). Doubles as reading history; [inLibrary] = user saved it. */
@Entity(tableName = "titles", primaryKeys = ["sourceId", "titleId"])
data class SavedTitle(
    val sourceId: String,
    val titleId: String,
    val title: String,
    val coverUrl: String? = null,
    val author: String = "",
    val inLibrary: Boolean = false,
    val lastChapterId: String? = null,
    val lastChapterName: String? = null,
    val lastPage: Int = 0,
    val lastReadAt: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books")
    suspend fun all(): List<Book>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): Book?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: String): Flow<Book?>

    @Upsert
    suspend fun upsert(books: List<Book>)

    @Query("UPDATE books SET progress = :progress, locator = :locator, lastReadAt = :at, progressUpdatedAt = :at WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Float, locator: String?, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}

@Dao
interface TitleDao {
    @Query("SELECT * FROM titles WHERE inLibrary = 1 ORDER BY title COLLATE NOCASE")
    fun observeLibrary(): Flow<List<SavedTitle>>

    @Query("SELECT * FROM titles WHERE lastReadAt > 0 ORDER BY lastReadAt DESC LIMIT :limit")
    fun observeHistory(limit: Int = 30): Flow<List<SavedTitle>>

    @Query("SELECT * FROM titles")
    suspend fun all(): List<SavedTitle>

    @Query("SELECT * FROM titles WHERE sourceId = :sourceId AND titleId = :titleId")
    suspend fun get(sourceId: String, titleId: String): SavedTitle?

    @Query("SELECT * FROM titles WHERE sourceId = :sourceId AND titleId = :titleId")
    fun observe(sourceId: String, titleId: String): Flow<SavedTitle?>

    @Upsert
    suspend fun upsert(titles: List<SavedTitle>)
}

@Database(entities = [Book::class, SavedTitle::class], version = 1, exportSchema = false)
abstract class FolioDb : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun titles(): TitleDao
    // ponytail: v1, no migrations yet. Bump version + add a Migration before changing an entity; never fallbackToDestructiveMigration (wipes progress).
}
