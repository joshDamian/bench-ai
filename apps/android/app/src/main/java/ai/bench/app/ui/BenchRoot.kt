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

    NavHost(
        nav,
        startDestination = "home",
        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
    ) {
        composable("home") {
            HomeScreen(store, onOpen = { nav.navigate("session/$it") }, onSettings = { nav.navigate("settings") })
        }
        composable("session/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val vm: SessionViewModel = viewModel(key = "session-$id", factory = factory { SessionViewModel(id, store) })
            SessionScreen(vm, onBack = { nav.popBackStack() })
        }
        composable("settings") { SettingsScreen(store, onBack = { nav.popBackStack() }) }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : ViewModel> factory(make: () -> T) = object : ViewModelProvider.Factory {
    override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
}
