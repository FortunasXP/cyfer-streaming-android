package app.cyfer.streaming.android.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Bottom nav slimmed to **5 primary tabs**. Secondary surfaces (Anime,
 * Calendar, Downloads) are reached from the Home quick-access strip and
 * deep-linked overlays — keeping the pill bar legible on every screen
 * width without truncating labels.
 */
sealed class Screen(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    data object Home : Screen("home", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    data object Browse : Screen("browse", "Browse", Icons.Filled.GridView, Icons.Outlined.GridView)
    data object Search : Screen("search", "Search", Icons.Filled.Search, Icons.Outlined.Search)
    data object MyList : Screen("my_list", "My List", Icons.Filled.Bookmark, Icons.Outlined.Bookmark)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Tune, Icons.Outlined.Tune)
}

val bottomNavScreens = listOf(
    Screen.Home,
    Screen.Browse,
    Screen.Search,
    Screen.MyList,
    Screen.Settings,
)
