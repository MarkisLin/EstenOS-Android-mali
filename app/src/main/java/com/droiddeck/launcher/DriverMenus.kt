package com.droiddeck.launcher

import android.app.Activity
import android.content.Context
import java.io.File
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.gpu.TurnipReleases
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.ui.DriverRow

/**
 * The Linux and Android driver menus: the drivers each can pick from, the release downloads the
 * last check found, and what each mode is set to. The launcher screen keeps one and hands its
 * rows to the mode settings dialog.
 */
internal class DriverMenus(private val activity: Activity, private val ui: Handler) {
    var linuxRows by mutableStateOf<List<DriverRow>>(emptyList())
    /** The latest Banners-Turnip release as each driver menu offers it (see [refreshReleaseRows]). */
    var linuxDownloads by mutableStateOf<List<com.droiddeck.launcher.ui.DownloadRow>>(emptyList())
    var androidDownloads by mutableStateOf<List<com.droiddeck.launcher.ui.DownloadRow>>(emptyList())
    var releaseStatus by mutableStateOf("Aún no comprobado; pulsa actualizar para buscar controladores nuevos")
    var releaseChecking by mutableStateOf(false)
    var canRestoreBundled by mutableStateOf(false)
    /** Asset name -> download percent, while it downloads. */
    private val releaseProgress = HashMap<String, Int>()
    var linuxSteam by mutableStateOf("")
    var androidRows by mutableStateOf<List<DriverRow>>(emptyList())
    var androidSelected by mutableStateOf("")

    fun refreshDrivers() {
        val lm = LinuxVulkanDriverManager(activity)
        fun origin(id: String) = if (TurnipReleases.isDownloaded(activity, id)) DriverRow.DOWNLOADED else DriverRow.IMPORTED
        linuxRows = LinuxVulkanDriver.optionValues(activity).map { id ->
            if (id.isEmpty()) {
                val autoId = LinuxVulkanDriver.automaticDriverId(activity)
                DriverRow(
                    "", "Automático",
                    if (autoId.isNotEmpty()) {
                        listOfNotNull(
                            lm.getDriverName(autoId),
                            lm.getDriverVersion(autoId).takeIf { it.isNotEmpty() },
                            lm.getKbaseProfileSummary(autoId).takeIf { it.isNotEmpty() },
                        ).joinToString(" · ")
                    } else {
                        LinuxRuntime.vulkanIcd(activity)?.let { "${it.name} · ${DeviceSupport.family().name}" }
                            ?: if (DeviceSupport.mali()) "instala un ICD PanVK compatible; DroidDeck lo seleccionará solo"
                            else "no hay un ICD incluido compatible con ${DeviceSupport.family().name}"
                    },
                    false,
                )
            }
            else DriverRow(
                id, lm.getDriverName(id),
                listOfNotNull(
                    lm.getDriverVersion(id).takeIf { it.isNotEmpty() },
                    lm.getMinGlibc(id).takeIf { it.isNotEmpty() }?.let { "glibc $it+" },
                    lm.getGuestBackend(id).takeIf { it.isNotEmpty() },
                    lm.getKbaseProfileSummary(id).takeIf { it.isNotEmpty() },
                    lm.getReleaseSummary(id).takeIf { it.isNotEmpty() },
                    lm.compatibilityIssue(id)?.let { "no compatible: $it" },
                ).joinToString(" · "),
                true, origin(id),
            )
        }
        linuxSteam = SessionPrefs.linuxDriver(activity, SessionService.MODE_STEAM)
        val td = TurnipDriver(activity)
        val auto = td.autoId()
        androidRows = buildList {
            if (DeviceSupport.hostVulkanBackend() == DeviceSupport.HostVulkanBackend.SYSTEM_VULKAN) {
                // Mali-first: the host compositor deliberately uses the vendor/system Vulkan HAL.
                // Do not expose Turnip bundles/imports here; they are Adreno-only and only add noise.
                add(DriverRow(
                    TurnipDriver.AUTO, "Vulkan del sistema",
                    "Controlador Android de ${DeviceSupport.family().name} · seleccionado automáticamente",
                    false,
                ))
            } else {
                add(DriverRow(
                    TurnipDriver.AUTO, "Automático - elegido según la GPU",
                    if (auto == "system") "Vulkan del sistema · ruta host ${DeviceSupport.family().name}" else "${td.displayName(auto)} (incluido)",
                    false,
                ))
                for (id in td.visibleBundled()) add(DriverRow(id, td.displayName(id), td.driverVersion(id), true, DriverRow.BUNDLED))
                for (id in td.enumerateImported()) add(DriverRow(id, td.displayName(id), td.driverVersion(id), true, origin(id)))
            }
        }
        canRestoreBundled = DeviceSupport.hostVulkanBackend() == DeviceSupport.HostVulkanBackend.ADRENO_TURNIP
            && td.hiddenBundled().isNotEmpty()
        androidSelected = SessionPrefs.androidDriver(activity)
        refreshReleaseRows()
    }

    /**
     * Import off the main thread - a driver zip is a few MB and the glibc check reads the whole
     * library - then say what happened. A refusal's message is the user-facing reason.
     */
    fun importDriver(uri: Uri, linux: Boolean) {
        val name = activity.displayNameOf(uri)
        Thread({
            val problem = try {
                if (linux) LinuxVulkanDriverManager(activity).installDriver(uri, name)
                else TurnipDriver(activity).installFromZip(uri, name)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: Exception) {
                Log.w(TAG, "importación de controlador", e)
                "Error al importar: ${e.message}"
            }
            ui.post {
                android.widget.Toast.makeText(
                    activity, problem ?: "Importado ${name ?: "controlador"}",
                    if (problem != null) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
                ).show()
                refreshDrivers()
            }
        }, "import-driver").start()
    }

    /**
     * Delete an imported or downloaded driver. A mode still set to it goes back to its default, so a
     * session never starts on a driver that is gone; a release download is forgotten, so the menu
     * offers it again.
     */
    fun deleteDriver(id: String, linux: Boolean) {
        if (linux) {
            LinuxVulkanDriverManager(activity).removeDriver(id)
            if (SessionPrefs.linuxDriver(activity, SessionService.MODE_STEAM) == id) {
                SessionPrefs.setLinuxDriver(activity, SessionService.MODE_STEAM, "")
            }
        } else {
            val td = TurnipDriver(activity)
            if (id in TurnipDriver.BUNDLED) td.hideBundled(id) else td.remove(id)
            if (SessionPrefs.androidDriver(activity) == id) SessionPrefs.setAndroidDriver(activity, TurnipDriver.AUTO)
        }
        TurnipReleases.forget(activity, id)
        android.widget.Toast.makeText(activity, "Eliminado ${id}", android.widget.Toast.LENGTH_SHORT).show()
        refreshDrivers()
    }

    /** The download entries and the refresh line, from what the last check found. */
    fun refreshReleaseRows() {
        val check = TurnipReleases.cached(activity)
        val lm = LinuxVulkanDriverManager(activity)
        val td = TurnipDriver(activity)
        fun rows(linux: Boolean) = check?.assets.orEmpty()
            .filter { it.linux == linux }
            .filter { a -> TurnipReleases.installedId(activity, a) { id -> if (linux) lm.isInstalled(id) else td.isInstalled(id) } == null }
            .map { a ->
                val mb = "%.1f MB".format(a.size / 1_048_576.0)
                com.droiddeck.launcher.ui.DownloadRow(a.name, "${a.source} ${a.tag}", "${a.label} · $mb", releaseProgress[a.name])
            }
        linuxDownloads = rows(linux = true)
        androidDownloads = rows(linux = false)
        if (!releaseChecking) releaseStatus = when (check) {
            null -> "Aún no comprobado; pulsa actualizar para buscar controladores nuevos"
            else -> "Último: " + check.latest.joinToString(" · ") { "${it.first} ${it.second}" } +
                (if (check.failed.isEmpty()) "" else " · no se pudo acceder a ${check.failed.joinToString()}") +
                " · comprobado ${ago(check.checkedAt)}"
        }
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "ahora mismo"
            m < 60 -> "hace $m min"
            m < 48 * 60 -> "hace ${m / 60} h"
            else -> "hace ${m / (24 * 60)} días"
        }
    }

    /** Only when the user taps refresh: nothing goes online on its own. */
    fun checkLatestTurnip() {
        if (releaseChecking) return
        releaseChecking = true
        releaseStatus = "Buscando ${TurnipReleases.sourceSummary()}…"
        Thread({
            val problem = try { TurnipReleases.refresh(activity); null } catch (e: Exception) {
                Log.w(TAG, "latest Turnip check", e); e.message ?: "falló la comprobación"
            }
            ui.post {
                releaseChecking = false
                refreshReleaseRows()
                if (problem != null) releaseStatus = "No se pudo comprobar: $problem"
            }
        }, "driver-release-check").start()
    }

    /** Download one release driver and import it through the same importer a picked zip uses. */
    fun downloadReleaseDriver(assetName: String) {
        val asset = TurnipReleases.cached(activity)?.assets?.firstOrNull { it.name == assetName } ?: return
        if (releaseProgress.containsKey(assetName)) return
        releaseProgress[assetName] = 0
        refreshReleaseRows()
        Thread({
            var file: java.io.File? = null
            val problem = try {
                file = TurnipReleases.download(activity, asset) { pct ->
                    ui.post { releaseProgress[assetName] = pct; refreshReleaseRows() }
                }
                val uri = Uri.fromFile(file)
                val id = if (asset.linux) LinuxVulkanDriverManager(activity)
                    .installReleaseDriver(uri, asset.name, asset.source, asset.tag)
                else TurnipDriver(activity).installFromZip(uri, asset.name)
                TurnipReleases.recordDownload(activity, asset, id)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: Exception) {
                Log.w(TAG, "descarga de controlador", e)
                "Error de descarga: ${e.message}"
            } finally {
                file?.let { com.droiddeck.launcher.core.FileUtils.delete(it) }
            }
            ui.post {
                releaseProgress.remove(assetName)
                android.widget.Toast.makeText(
                    activity, problem ?: if (DeviceSupport.mali())
                        "Instalado ${asset.name.removeSuffix(".zip")} · DroidDeck lo usará automáticamente si coincide con tu GPU"
                    else "Instalado ${asset.name.removeSuffix(".zip")}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                refreshDrivers()
            }
        }, "download-driver").start()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

internal fun Context.displayNameOf(uri: Uri): String? = if (uri.scheme == "file") uri.lastPathSegment else try {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
} catch (e: Exception) {
    null
}

