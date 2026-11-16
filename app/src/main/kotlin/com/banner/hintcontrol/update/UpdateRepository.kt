package com.banner.hintcontrol.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.banner.hintcontrol.BuildConfig
import com.banner.hintcontrol.data.SettingsRepository
import com.banner.hintcontrol.gateway.GatewayHttp
import com.banner.hintcontrol.gateway.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

@Serializable
data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

data class AppRelease(
    val tag: String,
    val version: AppVersion,
    val title: String,
    val notes: String,
    val publishedAt: String?,
    val pageUrl: String?,
    val apk: GhAsset,
    val checksum: GhAsset?,
    val prerelease: Boolean,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long, val latest: AppRelease?) : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class Verifying(val release: AppRelease) : UpdateState
    data class ReadyToInstall(val release: AppRelease, val file: File) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/** Checks this app's GitHub releases and downloads newer builds. */
class UpdateRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val http: OkHttpClient = GatewayHttp.base,
    private val repo: String = BuildConfig.UPDATE_REPO,
    private val installed: String = BuildConfig.VERSION_NAME,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    val installedVersion: AppVersion? get() = AppVersion.parse(installed)

    /** Called at app start: a downloaded APK is either installed by now or no longer tracked. */
    fun clearDownloads() {
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    /** Returns the newest release this install should be offered, or null when up to date. */
    suspend fun check(): AppRelease? {
        _state.value = UpdateState.Checking
        return try {
            val releases = fetchReleases()
            val s = settings.current()
            val best = pick(releases, s.includePrereleases)
            settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            val current = installedVersion
            if (best != null && current != null && best.version > current) {
                _state.value = UpdateState.Available(best)
                best
            } else {
                _state.value = UpdateState.UpToDate(System.currentTimeMillis(), best)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun fetchReleases(): List<GhRelease> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases?per_page=20")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HintPlus/$installed")
            .build()
        val r = http.await(request)
        if (!r.isSuccess) throw IllegalStateException("GitHub answered HTTP ${r.code}")
        return json.decodeFromString(ListSerializer(GhRelease.serializer()), r.body)
    }

    suspend fun download(release: AppRelease) {
        try {
            _state.value = UpdateState.Downloading(release, 0f)
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, release.apk.name)
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(release.apk.url).header("User-Agent", "HintPlus/$installed").build()
                http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Download failed (HTTP ${response.code})")
                    val body = response.body ?: throw IllegalStateException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: release.apk.size
                    body.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastEmit = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0 && done - lastEmit > 128 * 1024) {
                                    lastEmit = done
                                    _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }
            }
            _state.value = UpdateState.Verifying(release)
            verify(release, file)
            _state.value = UpdateState.ReadyToInstall(release, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Download failed", release)
        }
    }

    private suspend fun verify(release: AppRelease, file: File) {
        release.checksum?.let { asset ->
            val r = http.await(Request.Builder().url(asset.url).header("User-Agent", "HintPlus/$installed").build())
            if (!r.isSuccess) throw IllegalStateException("Couldn't read the checksum file (HTTP ${r.code})")
            val expected = r.body.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
            val actual = withContext(Dispatchers.IO) { sha256(file) }
            if (expected == null || expected != actual) {
                file.delete()
                throw IllegalStateException("Checksum mismatch — the download was not installed.")
            }
        }
        val info = withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
            } else {
                context.packageManager.getPackageArchiveInfo(file.path, 0)
            }
        }
        if (info == null || info.packageName != context.packageName) {
            file.delete()
            throw IllegalStateException("The downloaded file is not an update for this app.")
        }
    }

    fun markInstalling(release: AppRelease) {
        _state.value = UpdateState.Installing(release)
    }

    fun installFinished(success: Boolean, message: String?) {
        val s = _state.value
        val release = (s as? UpdateState.Installing)?.release
        _state.value = if (success) UpdateState.Idle else UpdateState.Failed(message ?: "Install cancelled", release)
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    companion object {
        fun pick(releases: List<GhRelease>, includePrereleases: Boolean): AppRelease? = releases
            .asSequence()
            .filter { !it.draft && (includePrereleases || !it.prerelease) }
            .mapNotNull { r ->
                val version = AppVersion.parse(r.tag) ?: return@mapNotNull null
                val apk = r.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return@mapNotNull null
                AppRelease(
                    tag = r.tag,
                    version = version,
                    title = r.name?.takeIf { it.isNotBlank() } ?: r.tag,
                    notes = r.body.orEmpty(),
                    publishedAt = r.publishedAt,
                    pageUrl = r.htmlUrl,
                    apk = apk,
                    checksum = r.assets.firstOrNull { it.name.equals(apk.name + ".sha256", ignoreCase = true) }
                        ?: r.assets.firstOrNull { it.name.endsWith(".sha256", ignoreCase = true) && it.name.contains(version.toString()) },
                    prerelease = r.prerelease,
                )
            }
            .maxByOrNull { it.version }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
