package nl.ihnatov.transcriber.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import nl.ihnatov.transcriber.data.AppContainer
import nl.ihnatov.transcriber.ui.components.Mono
import nl.ihnatov.transcriber.ui.components.Panel
import nl.ihnatov.transcriber.ui.record.RecordScreen
import nl.ihnatov.transcriber.ui.recordings.RecordingDetailScreen
import nl.ihnatov.transcriber.ui.recordings.RecordingsListScreen
import nl.ihnatov.transcriber.ui.settings.SettingsScreen
import nl.ihnatov.transcriber.ui.theme.Accent

/**
 * App-level navigation. Three top-level destinations (Library / Record /
 * Settings) plus a recording-detail destination pushed onto the stack
 * from Library.
 *
 * The bottom nav is a floating pill [Panel] rather than the old full-
 * width hairline-topped bar — converges with where Material 3 Expressive
 * already went (pill shapes, a soft accent "active indicator" behind the
 * selected icon) instead of the old system's deliberate flat/ruled
 * departure from Material. Icons are new too: the old editorial system's
 * "no icons, ever" rule doesn't carry over — Lit Field documents a
 * first-class `.icon` primitive, so a 3-tab bar with icon + label is now
 * in-register rather than a decorative violation.
 */
private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Record : Tab("record", "RECORD", Icons.Outlined.Mic)
    data object Library : Tab("library", "LIBRARY", Icons.AutoMirrored.Outlined.List)
    data object Settings : Tab("settings", "SETTINGS", Icons.Outlined.Settings)
}

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            LitBottomBar(
                currentRoute = currentRoute,
                onTabClick = { route ->
                    nav.navigate(route) {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.Record.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.Record.route) {
                RecordScreen(
                    container = container,
                    onRecordingFinished = { id, autoRun ->
                        // Auto-run hint as a route query so Detail can
                        // fire the transcribe button without a tap.
                        nav.navigate("recording/$id?auto=${if (autoRun) 1 else 0}")
                    },
                    onOpenLibrary = { nav.navigate(Tab.Library.route) },
                )
            }
            composable(Tab.Library.route) {
                RecordingsListScreen(
                    container = container,
                    onOpen = { id -> nav.navigate("recording/$id?auto=0") },
                    onStartRecording = { nav.navigate(Tab.Record.route) },
                )
            }
            composable(Tab.Settings.route) {
                SettingsScreen(container = container)
            }
            composable(
                route = "recording/{id}?auto={auto}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("auto") {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                ),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val autoRun = (entry.arguments?.getInt("auto") ?: 0) == 1
                RecordingDetailScreen(
                    container = container,
                    recordingId = id,
                    autoRun = autoRun,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun LitBottomBar(
    currentRoute: String?,
    onTabClick: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Panel(
            strong = true,
            shape = RoundedCornerShape(50),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(Tab.Record, Tab.Library, Tab.Settings).forEach { tab ->
                    val active = currentRoute == tab.route ||
                        // Detail screen is pushed from Library — treat as
                        // Library-active so the indicator stays put while
                        // viewing a recording.
                        (tab == Tab.Library && currentRoute?.startsWith("recording/") == true)
                    NavTabItem(
                        icon = tab.icon,
                        label = tab.label,
                        active = active,
                        onClick = { onTabClick(tab.route) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavTabItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(RoundedCornerShape(50))
                .background(if (active) Accent.copy(alpha = 0.16f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) Accent else ink.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp),
            )
        }
        // Bottom nav is the primary global affordance — users reported
        // both "I only see Record" and "I can't find Settings" with the
        // old, smaller editorial label size. Keep the bump: labelMedium
        // rather than the default labelLarge-sized Mono, full ink when
        // active so the OTHER tabs don't disappear at 55% alpha.
        Mono(
            label,
            color = if (active) ink else ink.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
