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
    val open: (String) -> Unit = { nav.navigate("session/$it") }

    Column(Modifier.fillMaxSize()) {
        NavHost(
            nav,
            startDestination = "home",
            modifier = Modifier.weight(1f),
            enterTransition = { if (initialState.destination.route in tabs && targetState.destination.route in tabs) fadeIn() else slideInHorizontally { it / 4 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
        ) {
            composable("home") { HomeScreen(store, onOpen = open, onNew = { newSession = true }, onSearch = { nav.navigate("sessions") }) }
            composable("sessions") { SessionsScreen(store, onOpen = open, onBack = { nav.popBackStack() }) }
            composable("projects") { ProjectsScreen(store, onOpen = { nav.navigate("project/$it") }, onAdd = { addProject = true }) }
            composable("settings") { SettingsScreen(store, onOpen = { nav.navigate(it) }) }
            composable("devices") { DevicesScreen(store, onBack = { nav.popBackStack() }) }
            composable("dashboard") { DashboardScreen(store, onBack = { nav.popBackStack() }) }
            composable("limit") { UsageLimitScreen(store, onBack = { nav.popBackStack() }) }
            composable("subagents/{scope}", arguments = listOf(navArgument("scope") { type = NavType.StringType })) { e ->
                SubagentsScreen(store, e.arguments?.getString("scope") ?: "default", onBack = { nav.popBackStack() })
            }
            composable("session/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val vm: SessionViewModel = viewModel(key = "session-$id", factory = factory { SessionViewModel(id, store) })
                SessionScreen(vm, onBack = { nav.popBackStack() }, onTask = { nav.navigate("task/$it") }, onTerminal = { nav.navigate("terminal/$id") })
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

    if (newSession) NewSessionSheet(store, onDismiss = { newSession = false }, onCreated = { newSession = false; open(it) })
    if (addProject) AddProjectSheet(store, onDismiss = { addProject = false }, onAdded = { addProject = false; nav.navigate("project/$it") })
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
