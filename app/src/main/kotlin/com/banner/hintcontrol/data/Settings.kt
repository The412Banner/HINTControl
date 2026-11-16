package com.banner.hintcontrol.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.banner.hintcontrol.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "app")

internal val storeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
enum class ThemeMode { System, Light, Dark }

@Serializable
data class RebootSchedule(
    val enabled: Boolean = false,
    val hour: Int = 4,
    val minute: Int = 0,
    /** ISO days, 1 = Monday … 7 = Sunday. */
    val days: Set<Int> = (1..7).toSet(),
) {
    val everyDay: Boolean get() = days.size == 7

    fun summary(): String {
        if (!enabled) return "Off"
        val time = "%02d:%02d".format(hour, minute)
        val dayText = when {
            everyDay -> "Every day"
            days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
            days == setOf(6, 7) -> "Weekends"
            else -> days.sorted().joinToString(", ") { DayOfWeek.of(it).name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
        }
        return "$dayText at $time"
    }
}

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val pureBlack: Boolean = false,
    /** Seconds between automatic refreshes while the app is open; 0 = only on request. */
    val refreshSeconds: Int = 5,
    val recordHistory: Boolean = true,
    val historyDays: Int = 30,
    /** Background check interval in minutes; 0 = off. */
    val backgroundMinutes: Int = 30,
    val alertOffline: Boolean = true,
    val alertReboot: Boolean = true,
    val alertWeakSignal: Boolean = false,
    val weakSinrThreshold: Int = 0,
    val alertNewDevice: Boolean = false,
    val autoUpdateCheck: Boolean = true,
    val includePrereleases: Boolean = BuildConfig.VERSION_NAME.contains('-'),
    val lastUpdateCheck: Long = 0,
    val skippedVersion: String? = null,
    val developerMode: Boolean = false,
    val placementSound: Boolean = true,
    val placementHaptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    val rebootSchedule: RebootSchedule = RebootSchedule(),
    val automationEnabled: Boolean = false,
    val automationToken: String = "",
)

class SettingsRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    private val key = stringPreferencesKey("settings_json")

    val settings: StateFlow<AppSettings> = store.data
        .map { prefs -> prefs[key]?.let(::decode) ?: AppSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = store.data.first()[key]?.let(::decode) ?: AppSettings()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val now = prefs[key]?.let(::decode) ?: AppSettings()
            prefs[key] = storeJson.encodeToString(AppSettings.serializer(), transform(now))
        }
    }

    private fun decode(text: String): AppSettings =
        runCatching { storeJson.decodeFromString(AppSettings.serializer(), text) }.getOrDefault(AppSettings())
}
