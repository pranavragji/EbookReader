package io.github.pranavragji.folio.sync

import io.github.pranavragji.folio.FolioApp
import io.github.pranavragji.folio.data.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SyncState(val running: Boolean = false, val lastSync: Long = 0, val error: String? = null)

// STUB: owned by the sources/sync agent. Keep these public signatures.
class SyncManager(private val app: FolioApp) {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    /** Fire-and-forget, debounced; no-op when no provider is configured. Safe to call often. */
    fun requestSync() {}

    suspend fun syncNow(): Result<Unit> = Result.success(Unit)

    /** Upload the book file to the user's cloud library so other devices can download it. */
    suspend fun uploadBook(book: Book): Result<Unit> = Result.success(Unit)
}
