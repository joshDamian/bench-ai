package ai.bench.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ai.bench.app.data.BenchApi
import ai.bench.app.data.BenchStore
import ai.bench.app.data.ServerConfig
import ai.bench.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Holds the one store for the server this phone is paired with. It outlives screens, so
 * the socket and the lists survive rotation and navigation, and it follows the app's
 * foreground: on coming back it checks the line at once rather than waiting for a retry.
 */
class BenchApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: Settings
        private set

    private val _store = MutableStateFlow<BenchStore?>(null)
    val store: StateFlow<BenchStore?> = _store.asStateFlow()

    /** Whether settings have been read yet, so the first frame is not the pairing screen. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Files shared into the app, waiting for a session to go to. */
    val pendingShare = MutableStateFlow<List<Uri>>(emptyList())

    var foreground = false
        private set

    private var notifierJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        Notifier.createChannels(this)

        appScope.launch {
            settings.server.distinctUntilChanged().collect { config -> use(config) }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                _store.value?.reconnectNow()
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground = false
            }
        })
    }

    private fun use(config: ServerConfig?) {
        _store.value?.stop()
        notifierJob?.cancel()
        val store = config?.let { BenchStore(BenchApi(it), appScope) }
        _store.value = store
        _loaded.value = true
        store ?: return
        store.start()
        // While the process lives, an approval that arrives with the app in the background
        // is a notification, so a phone in a pocket still hears that Claude is waiting.
        val prefs = getSharedPreferences("alerts", 0)
        notifierJob = appScope.launch {
            launch {
                store.newApprovals.collect { a ->
                    if (!foreground && prefs.getBoolean("needs", true)) {
                        val title = store.state.value.sessions.firstOrNull { it.id == a.session_id }?.title ?: "Session"
                        Notifier.approval(this@BenchApp, a, title)
                    }
                }
            }
            // A session leaving "running" finished its turn, or stopped with an error.
            var last = emptyMap<String, String>()
            store.state.collect { st ->
                val now = st.sessions.associate { it.id to it.status }
                if (!foreground && last.isNotEmpty()) for (s in st.sessions) {
                    if (last[s.id] != "running" || s.status == "running") continue
                    when {
                        s.status == "error" && prefs.getBoolean("errors", true) -> Notifier.turn(this@BenchApp, s.id, s.title, "Stopped with an error" + (s.error?.let { ": $it" } ?: ""))
                        s.status == "idle" && prefs.getBoolean("finished", false) -> Notifier.turn(this@BenchApp, s.id, s.title, "Finished")
                    }
                }
                last = now
            }
        }
        if (prefs.getBoolean("background", false)) ConnectionService.start(this)
    }

    companion object {
        lateinit var instance: BenchApp
            private set
    }
}
