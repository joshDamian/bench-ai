package ai.bench.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.navigation.navArgument
import ai.bench.app.BenchApp
import ai.bench.app.MainActivity
import ai.bench.app.data.BenchStore
import ai.bench.app.ui.session.SessionScreen
import ai.bench.app.ui.session.SessionViewModel
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.BenchTheme
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun BenchRoot(openSession: MutableStateFlow<String?>) {
    val app = BenchApp.instance
    val theme by app.settings.theme.collectAsState(null)
    val loaded by app.loaded.collectAsState()
    val store by app.store.collectAsState()

    BenchTheme(theme ?: "classic") {
        Box(Modifier.fillMaxSize().background(Bench.colors.bg)) {
            if (!loaded || theme == null) return@Box
            val s = store
            if (s == null) PairScreen() else Signed(s, openSession)
        }
    }
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun Signed(store: BenchStore, openSession: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val state by store.state.collectAsState()
    val target by openSession.collectAsState()

    // A token the server no longer knows (revoked on another device) goes back to pairing.
    LaunchedEffect(state.unauthorized) {
        if (state.unauthorized) BenchApp.instance.settings.forget()
    }
    LaunchedEffect(target) {
        target?.let {
            nav.navigate("session/$it") { launchSingleTop = true }
            openSession.value = null
        }
    }

    // Notifications are what tell you Claude is waiting while the phone is in a pocket.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (MainActivity.needsNotificationPermission) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.route
    val tabs = listOf("home", "projects", "settings")
    var newSession by remember { mutableStateOf(false) }
    var addProject by remember { mutableStateOf(false) }
    val askedNew by BenchApp.instance.requestNew.collectAsState()
    LaunchedEffect(askedNew) { if (askedNew) { newSession = true; BenchApp.instance.requestNew.value = false } }
    // Tablets and unfolded foldables: Home stays docked on the left, what you open shows beside it.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700
    val open: (String) -> Unit = {
        nav.navigate("session/$it") { if (wide) popUpTo("home"); launchSingleTop = true }
    }
    // Material 3 Expressive motion: screens move on springs, so they settle rather than stop.
    val move = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.85f, stiffness = 380f)
    val fade = androidx.compose.animation.core.spring<Float>(stiffness = 500f)

    androidx.compose.animation.SharedTransitionLayout {
    androidx.compose.runtime.CompositionLocalProvider(LocalShared provides this) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
    if (wide) {
        Box(Modifier.width(380.dp).fillMaxHeight()) {
            HomeScreen(store, onOpen = open, onNew = { newSession = true }, onSearch = { nav.navigate("sessions") }, selected = (back?.arguments?.getString("id")).takeIf { route == "session/{id}" })
        }
        androidx.compose.material3.VerticalDivider(color = Bench.colors.line)
    }
    Column(Modifier.weight(1f).fillMaxHeight()) {
        NavHost(
            nav,
            startDestination = "home",
            modifier = Modifier.weight(1f),
            enterTransition = { if (initialState.destination.route in tabs && targetState.destination.route in tabs) fadeIn(fade) else slideInHorizontally(move) { it / 3 } + fadeIn(fade) },
            exitTransition = { fadeOut(fade) + androidx.compose.animation.scaleOut(targetScale = 0.96f) },
            popEnterTransition = { fadeIn(fade) + androidx.compose.animation.scaleIn(initialScale = 0.96f) },
            popExitTransition = { slideOutHorizontally(move) { it / 3 } + fadeOut(fade) },
        ) {
            composable("home") {
                if (wide) EmptyDetail() else androidx.compose.runtime.CompositionLocalProvider(LocalNavScope provides this) {
                    HomeScreen(store, onOpen = open, onNew = { newSession = true }, onSearch = { nav.navigate("sessions") })
                }
            }
            composable("sessions") { SessionsScreen(store, onOpen = open, onBack = { nav.popBackStack() }) }
            composable("projects") { ProjectsScreen(store, onOpen = { nav.navigate("project/$it") }, onAdd = { addProject = true }, onAsk = { nav.navigate("ask") }) }
            composable("ask") { SessionsScreen(store, onOpen = open, onBack = { nav.popBackStack() }, askOnly = true) }
            composable("settings") { SettingsScreen(store, onOpen = { nav.navigate(it) }) }
            composable("devices") { DevicesScreen(store, onBack = { nav.popBackStack() }) }
            composable("dashboard") { DashboardScreen(store, onBack = { nav.popBackStack() }) }
            composable("diagnostics") { DiagnosticsScreen(store, onBack = { nav.popBackStack() }) }
            composable("notifications") { NotificationsScreen(onBack = { nav.popBackStack() }) }
            composable("limit") { UsageLimitScreen(store, onBack = { nav.popBackStack() }) }
            composable("subagents/{scope}", arguments = listOf(navArgument("scope") { type = NavType.StringType })) { e ->
                SubagentsScreen(store, e.arguments?.getString("scope") ?: "default", onBack = { nav.popBackStack() })
            }
            composable("session/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val vm: SessionViewModel = viewModel(key = "session-$id", factory = factory { SessionViewModel(id, store) })
                androidx.compose.runtime.CompositionLocalProvider(LocalNavScope provides this) {
                SessionScreen(vm, onBack = { nav.popBackStack() }, onTask = { nav.navigate("task/$it") }, onTerminal = { nav.navigate("terminal/$id") }, onResume = { nav.navigate("shell/claude/$id") })
                }
            }
            composable("terminal/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val ses = store.state.value.sessions.firstOrNull { it.id == id }
                TerminalScreen(store.api, "session=${java.net.URLEncoder.encode(id, "UTF-8")}&shell=1", ses?.title ?: "Session", ses?.cwd?.replace(Regex("^/(home|Users)/[^/]+"), "~") ?: "", onBack = { nav.popBackStack() })
            }
            composable("task/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                TaskScreen(store, id, onBack = { nav.popBackStack() }, onOpen = open,
                    onTerminal = { nav.navigate("shell/task/$id") }, onSubagents = { nav.navigate("subagents/task:$id") })
            }
            composable("project/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ProjectScreen(store, id, onBack = { nav.popBackStack() }, onOpen = open, onTask = { nav.navigate("task/$it") },
                    onTerminal = { nav.navigate("shell/project/$id") }, onSubagents = { nav.navigate("subagents/project:$id") })
            }
            composable("shell/{kind}/{id}", arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType })) { e ->
                val kind = e.arguments?.getString("kind") ?: return@composable
                val id = e.arguments?.getString("id") ?: return@composable
                val st = store.state.value
                if (kind == "claude") {
                    val ses = st.sessions.firstOrNull { it.id == id }
                    TerminalScreen(store.api, "session=${java.net.URLEncoder.encode(id, "UTF-8")}", ses?.title ?: "Session", "claude --resume", onBack = { nav.popBackStack() })
                    return@composable
                }
                val (title, place) = if (kind == "task") st.tasks.firstOrNull { it.id == id }.let { (it?.title ?: "Task") to (it?.branch ?: "") }
                    else st.projects.firstOrNull { it.id == id }.let { (it?.name ?: "Project") to (it?.path?.replace(Regex("^/(home|Users)/[^/]+"), "~") ?: "") }
                TerminalScreen(store.api, "$kind=${java.net.URLEncoder.encode(id, "UTF-8")}", title, place, onBack = { nav.popBackStack() })
            }
        }
        if (route in tabs) BenchNav(route ?: "home") { t ->
            nav.navigate(t) {
                popUpTo("home") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    }
    }
    }

    if (newSession) NewSessionSheet(store, onDismiss = { newSession = false }, onCreated = { newSession = false; open(it) })
    if (addProject) AddProjectSheet(store, onDismiss = { addProject = false }, onAdded = { addProject = false; nav.navigate("project/$it") })
}

/** Shared-element plumbing: set inside the layout and each screen's transition. */
val LocalShared = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }
val LocalNavScope = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.animation.AnimatedVisibilityScope?> { null }

/** A session's title travels from its row into the session's header and back. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedTitle(key: String?): Modifier {
    val shared = LocalShared.current ?: return this
    val scope = LocalNavScope.current ?: return this
    if (key == null) return this
    return with(shared) { this@sharedTitle.sharedBounds(rememberSharedContentState("title-$key"), scope, resizeMode = androidx.compose.animation.SharedTransitionScope.ResizeMode.ScaleToBounds()) }
}

/** The right pane on a wide screen before anything is open. */
@Composable
private fun EmptyDetail() {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text("Pick a session on the left, or start a new one.", color = Bench.colors.muted, fontSize = 15.sp)
    }
}

/** The navigation bar every tab shares, as its board draws it. */
@Composable
fun BenchNav(active: String, onPick: (String) -> Unit) {
    val c = Bench.colors
    NavigationBar(containerColor = c.bg, tonalElevation = 0.dp) {
        listOf(
            Triple("home", "Home", Icons.Outlined.Home),
            Triple("projects", "Projects", Icons.Outlined.Folder),
            Triple("settings", "Settings", Icons.Outlined.Settings),
        ).forEach { (id, label, icon) ->
            NavigationBarItem(
                selected = active == id,
                onClick = { onPick(id) },
                icon = { Icon(icon, null) },
                label = { Text(label, fontSize = 12.sp, fontWeight = if (active == id) FontWeight.SemiBold else FontWeight.Medium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.accent, selectedTextColor = c.text,
                    indicatorColor = c.accent.copy(alpha = 0.15f),
                    unselectedIconColor = c.muted, unselectedTextColor = c.muted,
                ),
            )
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : ViewModel> factory(make: () -> T) = object : ViewModelProvider.Factory {
    override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
}
