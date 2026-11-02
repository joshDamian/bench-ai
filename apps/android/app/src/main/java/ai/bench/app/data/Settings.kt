package ai.bench.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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

    suspend fun save(config: ServerConfig) = context.store.edit {
        it[baseKey] = config.baseUrl
        if (config.token != null) it[tokenKey] = config.token else it.remove(tokenKey)
        if (config.deviceName != null) it[deviceKey] = config.deviceName else it.remove(deviceKey)
    }

    suspend fun forget() = context.store.edit {
        it.remove(baseKey); it.remove(tokenKey); it.remove(deviceKey)
    }

    suspend fun setTheme(id: String) = context.store.edit { it[themeKey] = id }
}
