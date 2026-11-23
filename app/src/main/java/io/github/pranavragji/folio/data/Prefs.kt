package io.github.pranavragji.folio.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class OpdsSource(val id: String, val name: String, val url: String, val user: String = "", val pass: String = "")

@Serializable
data class SyncConfig(
    val provider: String = "none",   // none | google | webdav
    val account: String = "",        // google email / webdav user, shown in UI
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val webdavPass: String = "",     // app-private DataStore, allowBackup=false
    val lastSync: Long = 0,
)

@Serializable
data class Settings(
    val folders: List<String> = emptyList(),   // persisted SAF tree URIs
    val themeMode: Int = 0,                    // 0 system, 1 light, 2 dark
    val dynamicColor: Boolean = true,
    val amoled: Boolean = false,
    val librarySort: String = "recent",        // recent | title | author | added
    val libraryFilter: String = "all",         // all | books | comics | online
    val gridColumns: Int = 0,                  // 0 = adaptive
    // Text reader
    val readerTheme: String = "auto",          // auto | light | sepia | dark | black
    val fontScale: Int = 100,                  // percent
    val fontFamily: String = "serif",          // serif | sans | publisher
    val lineHeight: Float = 1.5f,
    val margin: Int = 24,                      // side margin in the reader (dp-ish)
    val justify: Boolean = true,
    val paginated: Boolean = true,             // false = continuous scroll
    // Comic reader
    val comicMode: String = "rtl",             // ltr | rtl | vertical | webtoon
    val keepScreenOn: Boolean = true,
    val volumeKeys: Boolean = true,
    val sync: SyncConfig = SyncConfig(),
    val opds: List<OpdsSource> = listOf(
        OpdsSource("gutenberg", "Project Gutenberg", "https://m.gutenberg.org/ebooks.opds/"),
    ),
)

private val Context.store by preferencesDataStore("settings")

/** All settings live as one JSON blob: typed, one place to add a field, defaults for free. */
class Prefs(private val ctx: Context) {
    private val key = stringPreferencesKey("json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decode(s: String?) =
        s?.let { runCatching { json.decodeFromString(Settings.serializer(), it) }.getOrNull() } ?: Settings()

    val settings: Flow<Settings> = ctx.store.data.map { decode(it[key]) }.distinctUntilChanged()

    suspend fun current(): Settings = settings.first()

    suspend fun update(f: (Settings) -> Settings) {
        ctx.store.edit { it[key] = json.encodeToString(Settings.serializer(), f(decode(it[key]))) }
    }
}
