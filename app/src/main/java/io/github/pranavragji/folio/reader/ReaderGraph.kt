package io.github.pranavragji.folio.reader

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

// STUB: owned by the readers agent. Must register "reader/{bookId}".
fun NavGraphBuilder.readerGraph(nav: NavHostController) {
    composable("reader/{bookId}") {}
}
