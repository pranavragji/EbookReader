package io.github.pranavragji.folio.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.pranavragji.folio.reader.readerGraph
import io.github.pranavragji.folio.ui.browse.browseGraph
import io.github.pranavragji.folio.ui.cloud.CloudScreen
import io.github.pranavragji.folio.ui.library.LibraryScreen
import io.github.pranavragji.folio.ui.settings.SettingsScreen

/** Route strings shared by every feature. Ids are URI-encoded; read them back with Uri.decode. */
object Routes {
    const val LIBRARY = "library"
    const val BROWSE = "browse"
    const val CLOUD = "cloud"
    const val SETTINGS = "settings"
    fun reader(bookId: String) = "reader/${Uri.encode(bookId)}"
    fun title(sourceId: String, titleId: String) = "title/${Uri.encode(sourceId)}/${Uri.encode(titleId)}"
}

private data class Tab(val route: String, val label: String, val on: ImageVector, val off: ImageVector)

private val tabs = listOf(
    Tab(Routes.LIBRARY, "Library", Icons.Filled.LocalLibrary, Icons.Outlined.LocalLibrary),
    Tab(Routes.BROWSE, "Browse", Icons.Filled.Explore, Icons.Outlined.Explore),
    Tab(Routes.CLOUD, "Sync", Icons.Filled.CloudSync, Icons.Outlined.CloudSync),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = tabs.any { it.route == route }

    NavigationSuiteScaffold(
        layoutType = if (topLevel) NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
        else NavigationSuiteType.None,
        navigationSuiteItems = {
            tabs.forEach { t ->
                val selected = route == t.route
                item(
                    selected = selected,
                    onClick = {
                        nav.navigate(t.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(if (selected) t.on else t.off, contentDescription = null) },
                    label = { Text(t.label) },
                )
            }
        },
    ) {
        NavHost(
            navController = nav,
            startDestination = Routes.LIBRARY,
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(160)) },
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onOpenBook = { nav.navigate(Routes.reader(it.id)) },
                    onOpenTitle = { nav.navigate(Routes.title(it.sourceId, it.titleId)) },
                    onBrowse = { nav.navigate(Routes.BROWSE) },
                )
            }
            composable(Routes.CLOUD) { CloudScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            browseGraph(nav)   // registers "browse" + "title/{sourceId}/{titleId}" + its own sub-routes
            readerGraph(nav)   // registers "reader/{bookId}"
        }
    }
}
