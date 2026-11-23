package io.github.pranavragji.folio.library

import io.github.pranavragji.folio.FolioApp
import io.github.pranavragji.folio.data.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// STUB: owned by the readers agent. Keep these public signatures.
class Scanner(private val app: FolioApp) {
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** Rescan every folder in Settings.folders plus app.downloadsDir; upsert new/changed, delete vanished. */
    suspend fun scanAll() {}

    /** Index one file that was just downloaded into app.downloadsDir. */
    suspend fun import(file: File): Book? = null
}
