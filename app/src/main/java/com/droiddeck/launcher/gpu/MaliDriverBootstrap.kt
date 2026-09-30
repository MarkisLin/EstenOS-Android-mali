package com.droiddeck.launcher.gpu

import android.content.Context
import android.net.Uri
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import java.io.IOException

/**
 * One-tap Mali setup: installs a checksum-verified glibc PanVK-Kbase release when the current
 * device has a profile DroidDeck explicitly qualifies and no compatible ICD is already installed.
 *
 * This deliberately does not guess for unknown Mali GPUs.  Automatic should mean "no menus" for
 * supported hardware, not "download a random driver and hope".
 */
object MaliDriverBootstrap {
    fun needed(context: Context): Boolean {
        if (DeviceSupport.guestGpuBackend() != DeviceSupport.GuestGpuBackend.MALI_KBASE) return false
        val manager = LinuxVulkanDriverManager(context)
        val chosen = SessionPrefs.linuxDriver(context, SessionService.MODE_STEAM)
        if (chosen.isNotEmpty()) {
            // A valid explicit choice is authoritative. A stale/missing/incompatible one must pass
            // through ensure() so it can be cleared before an automatic driver is resolved.
            return !manager.isInstalled(chosen) || manager.compatibilityIssue(chosen) != null
        }
        return LinuxVulkanDriver.automaticDriverId(context).isEmpty()
    }

    /**
     * One-shot self repair for failures that directly implicate an automatically managed Mali ICD.
     * Manual selections are never deleted. The caller should retry the session at most once after
     * this returns true; if the fresh trusted package fails too, the real diagnostic must be shown.
     */
    fun invalidateAutoManagedAfterPreflightFailure(context: Context, exitCode: Int): Boolean {
        if (exitCode !in setOf(78, 79, 82)) return false
        if (DeviceSupport.guestGpuBackend() != DeviceSupport.GuestGpuBackend.MALI_KBASE) return false

        // An explicit driver choice belongs to the user even if it originally came from Downloads.
        if (SessionPrefs.linuxDriver(context, SessionService.MODE_STEAM).isNotEmpty()) return false

        val id = LinuxVulkanDriver.automaticDriverId(context)
        if (id.isEmpty() || !TurnipReleases.isDownloaded(context, id)) return false

        val manager = LinuxVulkanDriverManager(context)
        manager.removeDriver(id)
        TurnipReleases.forget(context, id)
        android.util.Log.w("MaliDriverBootstrap", "removed auto-managed Mali ICD $id after preflight exit $exitCode; one clean reinstall is allowed")
        return true
    }

    /**
     * Returns the installed/usable driver id. Throws with a Spanish user-facing reason when this
     * phone has no integrated profile or the trusted release could not be fetched/verified.
     */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun ensure(context: Context, progress: (stage: String, percent: Int) -> Unit = { _, _ -> }): String {
        val manager = LinuxVulkanDriverManager(context)

        // Repair preference state before consulting Automatic. Otherwise a good automatic ICD can
        // make needed() look satisfied while the session still resolves a stale explicit id.
        val selected = SessionPrefs.linuxDriver(context, SessionService.MODE_STEAM)
        if (selected.isNotEmpty()) {
            if (manager.isInstalled(selected) && manager.compatibilityIssue(selected) == null) return selected
            SessionPrefs.setLinuxDriver(context, SessionService.MODE_STEAM, "")
        }
        LinuxVulkanDriver.automaticDriverId(context).takeIf { it.isNotEmpty() }?.let { return it }

        if (DeviceSupport.guestGpuBackend() != DeviceSupport.GuestGpuBackend.MALI_KBASE) {
            throw IOException("Este dispositivo no usa el backend Mali Kbase integrado")
        }

        val probe = MaliKbaseProbe.probe()
        val profile = MaliKbaseProfiles.forDevice(probe)
            ?: throw IOException("${MaliKbaseProfiles.readiness(probe)}. Todavía no hay un controlador automático calificado para este teléfono.")
        if (!profile.glibcReleaseIntegrated) {
            throw IOException("${profile.displayGpu} está reconocida, pero su controlador PanVK-Kbase glibc todavía es experimental y no se instala automáticamente.")
        }

        progress("Buscando el controlador Mali correcto…", -1)
        val check = try {
            TurnipReleases.refresh(context)
        } catch (e: Exception) {
            TurnipReleases.cached(context) ?: throw IOException("No se pudo consultar el controlador Mali: ${e.message}", e)
        }

        val asset = check.assets.firstOrNull { a ->
            a.linux && a.source == MaliKbaseProfiles.RELEASE_SOURCE_LABEL &&
                MaliKbaseProfiles.fromReleaseTag(a.tag)?.id == profile.id
        } ?: throw IOException("No se encontró una release glibc calificada para ${profile.displayGpu}. Actualiza DroidDeck o inténtalo más tarde.")

        TurnipReleases.installedId(context, asset) { manager.isInstalled(it) }?.let { id ->
            if (manager.compatibilityIssue(id) == null) return id
        }

        var archive: java.io.File? = null
        try {
            progress("Descargando ${profile.displayGpu} / PanVK-Kbase…", 0)
            archive = TurnipReleases.download(context, asset) { pct ->
                progress("Descargando ${profile.displayGpu} / PanVK-Kbase…", pct)
            }
            progress("Verificando e instalando el controlador Mali…", -1)
            val id = manager.installReleaseDriver(Uri.fromFile(archive), asset.name, asset.source, asset.tag)
            manager.compatibilityIssue(id)?.let { issue ->
                manager.removeDriver(id)
                throw IOException("El controlador descargado no coincide con este teléfono: $issue")
            }
            TurnipReleases.recordDownload(context, asset, id)
            return id
        } finally {
            archive?.let { FileUtils.delete(it) }
        }
    }
}
