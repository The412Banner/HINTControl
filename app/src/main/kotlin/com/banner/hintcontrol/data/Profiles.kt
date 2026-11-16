package com.banner.hintcontrol.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.banner.hintcontrol.gateway.GatewayKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

@Serializable
data class GatewayProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val kind: GatewayKind,
    val username: String = "admin",
    /** Keystore-encrypted password (see [SecretCipher]); null = not saved. */
    val passwordCipher: String? = null,
    val model: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isDemo: Boolean get() = kind == GatewayKind.Demo
    val hasSavedPassword: Boolean get() = passwordCipher != null
}

data class ProfilesState(
    val profiles: List<GatewayProfile> = emptyList(),
    val activeId: String? = null,
    val loaded: Boolean = false,
) {
    val active: GatewayProfile? get() = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
}

class ProfileRepository(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
    scope: CoroutineScope,
) {
    private val listKey = stringPreferencesKey("profiles_json")
    private val activeKey = stringPreferencesKey("active_profile")
    private val listSerializer = ListSerializer(GatewayProfile.serializer())

    val state: StateFlow<ProfilesState> = store.data
        .map { prefs ->
            ProfilesState(
                profiles = prefs[listKey]?.let { runCatching { storeJson.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty(),
                activeId = prefs[activeKey],
                loaded = true,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, ProfilesState())

    val active: StateFlow<GatewayProfile?> = state
        .map { it.active }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun save(profile: GatewayProfile, makeActive: Boolean = true) {
        store.edit { prefs ->
            val list = decode(prefs).filterNot { it.id == profile.id } + profile
            prefs[listKey] = storeJson.encodeToString(listSerializer, list.sortedBy { it.createdAt })
            if (makeActive) prefs[activeKey] = profile.id
        }
    }

    suspend fun setActive(id: String) {
        store.edit { it[activeKey] = id }
    }

    suspend fun delete(id: String) {
        store.edit { prefs ->
            val list = decode(prefs).filterNot { it.id == id }
            prefs[listKey] = storeJson.encodeToString(listSerializer, list)
            if (prefs[activeKey] == id) {
                list.firstOrNull()?.let { prefs[activeKey] = it.id } ?: prefs.remove(activeKey)
            }
        }
    }

    suspend fun setPassword(id: String, password: String?) {
        store.edit { prefs ->
            val list = decode(prefs).map {
                if (it.id == id) it.copy(passwordCipher = password?.let(cipher::encrypt)) else it
            }
            prefs[listKey] = storeJson.encodeToString(listSerializer, list)
        }
    }

    fun password(profile: GatewayProfile): String? = profile.passwordCipher?.let(cipher::decrypt)

    fun encrypt(password: String): String? = cipher.encrypt(password)

    private fun decode(prefs: Preferences): List<GatewayProfile> =
        prefs[listKey]?.let { runCatching { storeJson.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()
}
