package io.github.pranavragji.folio.ui.theme

import androidx.compose.ui.graphics.Color

/** Page/ink pairs for Settings.readerTheme. Readers can reuse these so preview == reality. */
data class ReaderPalette(val id: String, val label: String, val page: Color, val ink: Color)

val ReaderPalettes = listOf(
    ReaderPalette("light", "Paper", Color(0xFFFBF9F5), Color(0xFF1F1D1A)),
    ReaderPalette("sepia", "Sepia", Color(0xFFF2E7D3), Color(0xFF433422)),
    ReaderPalette("dark", "Dusk", Color(0xFF1E1D1B), Color(0xFFD9D3CA)),
    ReaderPalette("black", "Night", Color(0xFF000000), Color(0xFFB8B2AA)),
)

/** "auto" follows the app's light/dark state. */
fun readerPalette(id: String, dark: Boolean): ReaderPalette =
    ReaderPalettes.firstOrNull { it.id == id } ?: ReaderPalettes[if (dark) 2 else 0]
