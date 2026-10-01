package com.droiddeck.launcher.gpu

import android.content.Context
import android.net.Uri
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
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
        val auto = LinuxVulkanDriver.automaticDriverId(context)
        if (auto.isEmpty()) return true
        val profile = MaliKbaseProfiles.forDevice(MaliKbaseProbe.probe())
        return MaliKbaseProfiles.isPinnedG57(profile)
            && manager.isAutoManagedTrustedMaliDriver(auto)
            && !manager.isCurrentPinnedG57Driver(auto)
    }

    /**
     * One-shot self repair for failures that directly implicate an automatically managed Mali ICD.
     * Manual selections are never deleted. The caller should retry the session at most once after
     * this returns true; if the fresh trusted package fails too, the real diagnostic must be shown.
     */
    fun invalidateAutoManagedAfterPreflightFailure(context: Context, exitCode: Int): Boolean {
        if (exitCode !in setOf(78, 79, 82, 84)) return false
        if (DeviceSupport.guestGpuBackend() != DeviceSupport.GuestGpuBackend.MALI_KBASE) return false

        // An explicit driver choice belongs to the user even if it originally came from Downloads.
        if (SessionPrefs.linuxDriver(context, SessionService.MODE_STEAM).isNotEmpty()) return false

        val id = LinuxVulkanDriver.automaticDriverId(context)
        if (id.isEmpty()) return false

        val manager = LinuxVulkanDriverManager(context)
        if (!manager.isAutoManagedTrustedMaliDriver(id)) return false
        manager.removeDriver(id)
        if (TurnipReleases.isDownloaded(context, id)) TurnipReleases.forget(context, id)
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
        if (DeviceSupport.guestGpuBackend() != DeviceSupport.GuestGpuBackend.MALI_KBASE) {
            throw IOException("Este dispositivo no usa el backend Mali Kbase integrado")
        }

        val probe = MaliKbaseProbe.probe()
        val profile = MaliKbaseProfiles.forDevice(probe)
            ?: throw IOException("${MaliKbaseProfiles.readiness(probe)}. Todavía no hay un controlador automático calificado para este teléfono.")

        LinuxVulkanDriver.automaticDriverId(context).takeIf { it.isNotEmpty() }?.let { auto ->
            if (!MaliKbaseProfiles.isPinnedG57(profile) || manager.isCurrentPinnedG57Driver(auto)) {
                return auto
            }
            // Replace only our own obsolete G57 package. A manually imported compatible ICD remains
            // authoritative even when Automatic selected it.
            if (manager.isAutoManagedTrustedMaliDriver(auto)) {
                progress("Actualizando el controlador Mali-G57 para Wayland…", -1)
                manager.removeDriver(auto)
                if (TurnipReleases.isDownloaded(context, auto)) TurnipReleases.forget(context, auto)
            } else {
                return auto
            }
        }
        if (!profile.glibcReleaseIntegrated) {
            throw IOException("${profile.displayGpu} está reconocida, pero su controlador PanVK-Kbase glibc todavía es experimental y no se instala automáticamente.")
        }

        // Valhall v9/JM uses a different kernel frontend from modern CSF devices. Use the one
        // immutable G57 glibc package validated for PRoot, verify its SHA-256 locally, then let the
        // existing ldd/vulkaninfo/Zink/gamescope preflight decide whether this exact phone is safe.
        if (MaliKbaseProfiles.isPinnedG57(profile)) {
            return ensurePinnedG57(context, manager, profile, progress)
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

    @Throws(IOException::class, IllegalArgumentException::class)
    private fun ensurePinnedG57(
        context: Context,
        manager: LinuxVulkanDriverManager,
        profile: MaliKbaseProfiles.Profile,
        progress: (stage: String, percent: Int) -> Unit,
    ): String {
        val archive = java.io.File(context.cacheDir, "panvk-g57-v1.0.0-glibc-async.zip")
        try {
            progress("Descargando ${profile.displayGpu} / PanVK JM…", 0)
            val ok = Downloader.downloadFile(
                MaliKbaseProfiles.G57_RELEASE_URL,
                archive,
                true,
            ) { fraction ->
                val pct = if (fraction == null || fraction < 0f) -1
                else (fraction * 100f).toInt().coerceIn(0, 100)
                progress("Descargando ${profile.displayGpu} / PanVK JM…", pct)
            }
            if (!ok || !archive.isFile) {
                throw IOException("No se pudo descargar el controlador experimental Mali-G57 JM.")
            }

            progress("Verificando SHA-256 del controlador Mali-G57…", -1)
            val actual = Hashes.sha256(archive)
            if (!actual.equals(MaliKbaseProfiles.G57_RELEASE_SHA256, ignoreCase = true)) {
                FileUtils.delete(archive)
                throw IOException("La descarga Mali-G57 no pasó la verificación SHA-256.")
            }

            progress("Instalando PanVK Mali-G57 JM (glibc)…", -1)
            val id = manager.installReleaseDriver(
                Uri.fromFile(archive),
                "PanVK Mali-G57 JM glibc async",
                MaliKbaseProfiles.G57_RELEASE_SOURCE_LABEL,
                MaliKbaseProfiles.G57_RELEASE_TAG,
            )
            manager.compatibilityIssue(id)?.let { issue ->
                manager.removeDriver(id)
                throw IOException("El controlador Mali-G57 descargado no coincide con este teléfono: $issue")
            }
            return id
        } finally {
            FileUtils.delete(archive)
        }
    }
}
