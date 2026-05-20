package nl.ihnatov.transcriber.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import nl.ihnatov.transcriber.data.AppContainer
import nl.ihnatov.transcriber.ui.components.Mono
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
 * Per the editorial design spec, the bottom navigation is the spec's
 * "if you keep BottomNavigation, restyle..." fallback — paper background,
 * single hairline top border, mono-caps labels in ink with an Accent
 * underline on the active tab. No M3 NavigationBar elevation, ripple
 * splash, or pill indicator.
 */
private sealed class Tab(val route: String, val label: String) {
    data object Record : Tab("record", "RECORD")
    data object Library : Tab("library", "LIBRARY")
    data object Settings : Tab("settings", "SETTINGS")
}

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            EditorialBottomBar(
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

/**
 * Restyled bottom nav per design spec §6. Paper background, no elevation,
 * a single 1-dp hairline along the top, mono-caps labels. The active tab
 * label is full-ink and gets a 1.5-dp Accent underline; inactive tabs are
 * ink-soft. No M3 NavigationBar ripple/pill — just `clickable`.
 */
@Composable
private fun EditorialBottomBar(
    currentRoute: String?,
    onTabClick: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            // Subtle paper-edge tint so the nav strip reads as its own
            // surface and doesn't blur into the inverse footer above it
            // (the spec wants pure paper, but pure paper on paper meant
            // users on the Record screen saw the inverse footer's RECORD
            // label and never noticed the nav row underneath — they
            // thought RECORD was the only button).
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // navigationBarsPadding so the tab row sits ABOVE the gesture
            // bar / nav bar instead of underneath it. Without this the
            // bottom of the row was getting eaten by Android's home-
            // indicator inset on modern phones and the row looked
            // truncated.
            .navigationBarsPadding(),
    ) {
        // 1.5-dp ink rule above (was Hairline) — visually heavier than
        // the row-to-row hairlines elsewhere, so the bottom nav clearly
        // reads as a top-level joint, distinct from the inverse footer.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(MaterialTheme.colorScheme.onBackground),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(Tab.Record, Tab.Library, Tab.Settings).forEach { tab ->
                val active = currentRoute == tab.route ||
                    // Detail screen is pushed from Library — treat as
                    // Library-active so the underline stays put while
                    // viewing a recording.
                    (tab == Tab.Library && currentRoute?.startsWith("recording/") == true)
                NavTabItem(label = tab.label, active = active, onClick = { onTabClick(tab.route) })
            }
        }
    }
}

@Composable
private fun NavTabItem(label: String, active: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    val color = if (active) ink else ink.copy(alpha = 0.75f)
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            // width(IntrinsicSize.Max) constrains the Column to the
            // widest child's intrinsic width (the label text). Without
            // this, the underline Box's fillMaxWidth() below propagated
            // a max-width constraint that absorbed the entire SpaceEvenly
            // Row — the active tab took 100% of the row and the other
            // two tabs were pushed past the right edge of the screen.
            .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Bottom nav is the primary global affordance — users reported
        // both "I only see Record" and "I can't find Settings" with the
        // editorial labelLarge (10.5sp) size. We explicitly break from
        // the editorial scale here and use a hand-built TextStyle at
        // 14.sp Mono SemiBold — large enough to be a button label,
        // small enough to stay on-brand. Active = full ink + SemiBold,
        // inactive = 75% alpha so the OTHER tabs don't disappear.
        Text(
            text = label,
            color = color,
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = nl.ihnatov.transcriber.ui.theme.IbmPlexMono,
                fontSize = 14.sp,
                fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold
                else androidx.compose.ui.text.font.FontWeight.Medium,
                letterSpacing = 0.6.sp,
            ),
        )
        // Accent underline on the active tab. Bumped to 2.5dp so it's
        // visible without squinting. Always-rendered Box keeps the
        // layout stable when toggling; inactive tabs render at
        // transparent so the row height doesn't shift.
        Box(
            Modifier
                .height(2.5.dp)
                .padding(horizontal = 2.dp)
                .background(if (active) Accent else androidx.compose.ui.graphics.Color.Transparent)
                .fillMaxWidth(),
        )
    }
}
