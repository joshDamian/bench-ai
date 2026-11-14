package ai.bench.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("bench")

/**
 * What survives a restart: the server, the device token, and the theme. The token is only
 * ever readable by this app (allowBackup is off, so it is not copied off the phone).
 */
class Settings(private val context: Context) {
    private val baseKey = stringPreferencesKey("base_url")
    private val tokenKey = stringPreferencesKey("token")
    private val deviceKey = stringPreferencesKey("device_name")
    private val themeKey = stringPreferencesKey("theme")

    val server: Flow<ServerConfig?> = context.store.data.map { p ->
        p[baseKey]?.let { ServerConfig(it, p[tokenKey], p[deviceKey]) }
    }

    val theme: Flow<String> = context.store.data.map { it[themeKey] ?: "classic" }

    suspend fun current(): ServerConfig? = server.first()

    /** Every server this phone has paired with (Node and Go, say), one per line, the current one included. */
    private val listKey = stringPreferencesKey("servers")
    private fun tokenOf(url: String) = stringPreferencesKey("token:$url")
    private fun deviceOf(url: String) = stringPreferencesKey("device:$url")
    private fun listOf(p: androidx.datastore.preferences.core.Preferences): List<String> {
        val list = p[listKey]?.split("\n")?.filter { it.isNotBlank() }.orEmpty().toMutableList()
        p[baseKey]?.let { if (it !in list) list.add(0, it) }
        return list
    }

    val servers: Flow<List<String>> = context.store.data.map { listOf(it) }

    /** Set while pairing with one more server; the pair screen shows over the app until it's done. */
    val adding = MutableStateFlow(false)

    suspend fun save(config: ServerConfig) {
        context.store.edit {
            val current = it[baseKey]
            // Keep the server being left, so you can switch back to it.
            if (current != null && current != config.baseUrl) {
                it[tokenKey]?.let { t -> it[tokenOf(current)] = t }
                it[deviceKey]?.let { d -> it[deviceOf(current)] = d }
            }
            val list = listOf(it).let { l -> if (config.baseUrl in l) l else l + config.baseUrl }
            it[listKey] = list.joinToString("\n")
            it[baseKey] = config.baseUrl
            if (config.token != null) { it[tokenKey] = config.token; it[tokenOf(config.baseUrl)] = config.token } else { it.remove(tokenKey); it.remove(tokenOf(config.baseUrl)) }
            if (config.deviceName != null) { it[deviceKey] = config.deviceName; it[deviceOf(config.baseUrl)] = config.deviceName } else { it.remove(deviceKey); it.remove(deviceOf(config.baseUrl)) }
        }
        adding.value = false
    }

    /** Switches to another saved server. */
    suspend fun switchTo(url: String) {
        val p = context.store.data.first()
        if (p[baseKey] == url) return
        save(ServerConfig(url, p[tokenOf(url)], p[deviceOf(url)]))
    }

    /** Forgets the current server, then moves to the next saved one if there is one. */
    suspend fun forget() = context.store.edit {
        val current = it[baseKey]
        it.remove(baseKey); it.remove(tokenKey); it.remove(deviceKey)
        if (current != null) { it.remove(tokenOf(current)); it.remove(deviceOf(current)) }
        val rest = listOf(it).filter { u -> u != current }
        it[listKey] = rest.joinToString("\n")
        rest.firstOrNull()?.let { next ->
            it[baseKey] = next
            it[tokenOf(next)]?.let { t -> it[tokenKey] = t }
            it[deviceOf(next)]?.let { d -> it[deviceKey] = d }
        }
    }

    suspend fun setTheme(id: String) = context.store.edit { it[themeKey] = id }
}
