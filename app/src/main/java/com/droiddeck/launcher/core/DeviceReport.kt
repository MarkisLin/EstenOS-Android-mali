package com.droiddeck.launcher.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.MaliKbaseProbe
import com.droiddeck.launcher.gpu.MaliKbaseProfiles
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `device.txt`: diagnóstico reproducible de la sesión. No incluye identificadores personales ni
 * cuentas; solo hardware, ROM, backend gráfico y los ajustes relevantes de DroidDeck.
 */
object DeviceReport {
    private const val TAG = "DeviceReport"

    fun write(context: Context, target: File, mode: String) {
        try {
            target.writeText(build(context, mode))
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo escribir $target", e)
        }
    }

    fun build(context: Context, mode: String): String {
        val b = StringBuilder()
        fun h(title: String) {
            b.append('\n').append(title).append('\n').append("-".repeat(title.length)).append('\n')
        }
        fun k(key: String, value: Any?) {
            b.append(key.padEnd(30)).append(value ?: "desconocido").append('\n')
        }

        b.append("Informe de sesión de DroidDeck\n")
        b.append("=============================\n")
        k("Generado", SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(Date()))
        k("Modo de sesión", when (mode) {
            SessionService.MODE_DESKTOP -> "compatibilidad heredada redirigida a Steam"
            SessionService.MODE_RUN -> "programa bajo gamescope"
            else -> "Steam (gamescope)"
        })

        h("Aplicación")
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            k("Versión", "${info.versionName} (${info.longVersionCode})")
        }
        k("Paquete", context.packageName)
        k("targetSdk", context.applicationInfo.targetSdkVersion)
        k("Bibliotecas nativas", context.applicationInfo.nativeLibraryDir)

        h("Dispositivo")
        k("Modelo", "${Build.MANUFACTURER} ${Build.MODEL}")
        k("Dispositivo / producto", "${Build.DEVICE} / ${Build.PRODUCT}")
        k("Placa / hardware", "${Build.BOARD} / ${Build.HARDWARE}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            k("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        k("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        k("Parche de seguridad", Build.VERSION.SECURITY_PATCH)
        k("Compilación", Build.DISPLAY)
        k("Fingerprint", Build.FINGERPRINT)
        k("Kernel", System.getProperty("os.version"))
        k("ABIs", Build.SUPPORTED_ABIS.joinToString(", "))

        h("CPU y memoria")
        k("Núcleos", CpuCores.all.size)
        b.append("Frecuencias máximas".padEnd(30))
        b.append(CpuCores.all.joinToString(", ") { c ->
            "cpu$c " + (CpuCores.maxGhz(c)?.let { String.format(Locale.US, "%.2f GHz", it) } ?: "?")
        })
        b.append('\n')
        runCatching {
            val mi = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            k("RAM total", FileUtils.sizeToString(mi.totalMem))
            k("RAM disponible", FileUtils.sizeToString(mi.availMem))
        }
        runCatching {
            val fs = StatFs(context.filesDir.path)
            k("Espacio libre de la app", FileUtils.sizeToString(fs.availableBlocksLong * fs.blockSizeLong))
        }
        runCatching {
            val fs = StatFs(Environment.getExternalStorageDirectory().path)
            k("Espacio compartido libre", FileUtils.sizeToString(fs.availableBlocksLong * fs.blockSizeLong))
        }

        h("GPU")
        k("Familia", DeviceSupport.family().name)
        k("Vulkan del host", DeviceSupport.hostVulkanBackend().name)
        k("Backend GPU del guest", DeviceSupport.guestGpuBackend().name)
        k("Nodo DRM utilizable", DeviceSupport.usableDrmRenderNode() ?: "ninguno")
        k("Estado", DeviceSupport.supportSummary())
        k("KGSL gpu_model", readSys("/sys/class/kgsl/kgsl-3d0/gpu_model"))
        k("KGSL chip id", readSys("/sys/class/kgsl/kgsl-3d0/gpu_chipid"))
        k("Dispositivo Mali", listOf("/dev/mali0", "/sys/class/misc/mali0").firstOrNull { File(it).exists() } ?: "no visible")
        if (DeviceSupport.mali()) {
            val kbase = MaliKbaseProbe.probe()
            k("Sonda Kbase", kbase.shortSummary())
            k("Frontend / UAPI", "${kbase.frontendName} / ${kbase.uapi}")
            k("Product ID", kbase.productCodeHex)
            if (kbase.productId != 0L) k("Product ID Kbase", kbase.productIdHex)
            if (kbase.gpuId != 0L) k("GPU ID bruto", kbase.gpuIdHex)
            k("Núcleos shader", kbase.shaderCores)
            k("Perfil reconocido", MaliKbaseProfiles.forDevice(kbase)?.id ?: "sin perfil calificado")
            k("Madurez", MaliKbaseProfiles.readiness(kbase))
        }
        k("HAL Vulkan del sistema", listOf(
            "/vendor/lib64/hw/vulkan.mali.so", "/vendor/lib/hw/vulkan.mali.so",
            "/vendor/lib64/hw/vulkan.adreno.so", "/vendor/lib/hw/vulkan.adreno.so",
        ).firstOrNull { File(it).exists() } ?: "no encontrado en rutas conocidas")

        h("Memoria")
        val totalRam = MobilePerformance.totalRamBytes(context)
        k("RAM visible para Android", if (totalRam > 0) String.format(Locale.US, "%.2f GB", totalRam / 1_000_000_000.0) else "desconocida")
        k("Requisito DroidDeck", "mínimo 4 GB nominales")
        k("Compatibilidad de RAM", MobilePerformance.minimumRamIssue(totalRam) ?: "compatible")

        h("Pantalla")
        k("Salida de sesión", SessionState.outputSize?.let { "${it.first}x${it.second}" })
        k("Frecuencia", String.format(Locale.US, "%.2f Hz", SessionState.refreshHz))
        k("Proporción", SessionPrefs.shapeMode(context))
        val mobilePlan = MobilePerformance.plan(context, SessionPrefs.performanceProfile(context))
        k("Perfil móvil", MobilePerformance.summary(context, SessionPrefs.performanceProfile(context)))
        k("Motivo del perfil", mobilePlan.reason)
        k("Resolución de Steam", SessionPrefs.customResolution(context, SessionService.MODE_STEAM)?.let { "${it.first}x${it.second}" }
            ?: if (SessionPrefs.resolutionCap(context, SessionService.MODE_STEAM) == SessionPrefs.RESOLUTION_AUTO)
                "automática · máximo ${SessionPrefs.effectiveResolutionCap(context, SessionService.MODE_STEAM)}p"
            else "límite ${SessionPrefs.resolutionCap(context, SessionService.MODE_STEAM)}p")
        k("Plegable", context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle"))

        h("Controladores")
        val turnip = TurnipDriver(context)
        val androidChoice = SessionPrefs.androidDriver(context)
        if (DeviceSupport.hostVulkanBackend() == DeviceSupport.HostVulkanBackend.SYSTEM_VULKAN) {
            k("Controlador de pantalla", "Vulkan del sistema Android (${DeviceSupport.family().name})")
            if (androidChoice.isNotEmpty()) k("Selección AdrenoTools ignorada", androidChoice)
        } else {
            val selected = if (androidChoice.isEmpty()) turnip.autoId() else androidChoice
            k("Controlador de pantalla", selected)
            k("Nombre / versión", "${turnip.displayName(selected)} ${turnip.driverVersion(selected)}".trim())
        }

        val lm = LinuxVulkanDriverManager(context)
        val configuredLinux = SessionPrefs.linuxDriver(context, SessionService.MODE_STEAM)
        val automaticLinux = LinuxVulkanDriver.automaticDriverId(context)
        val effectiveLinux = configuredLinux.ifEmpty { automaticLinux }
        val runtimeDefault = LinuxRuntime.vulkanIcd(context)?.name ?: "sin ICD incluido compatible con ${DeviceSupport.family().name}"
        val linuxLabel = if (effectiveLinux.isEmpty()) {
            "Automático · runtime ($runtimeDefault)"
        } else {
            buildString {
                if (configuredLinux.isEmpty()) append("Automático · ")
                append("${lm.getDriverName(effectiveLinux)} ${lm.getDriverVersion(effectiveLinux)}".trim())
                lm.getMinGlibc(effectiveLinux).takeIf { it.isNotEmpty() }?.let { append(" · glibc $it+") }
                lm.getGuestBackend(effectiveLinux).takeIf { it.isNotEmpty() }?.let { append(" · $it") }
                lm.getKbaseProfileSummary(effectiveLinux).takeIf { it.isNotEmpty() }?.let { append(" · $it") }
                lm.getReleaseSummary(effectiveLinux).takeIf { it.isNotEmpty() }?.let { append(" · $it") }
                if (!lm.isInstalled(effectiveLinux)) append(" · FALTA")
                else lm.compatibilityIssue(effectiveLinux)?.let { append(" · INCOMPATIBLE: $it") }
            }
        }
        k("Controlador Linux (Steam)", linuxLabel)
        k("ICD propio del runtime", LinuxRuntime.vulkanIcd(context)?.path ?: "ninguno")

        h("Entorno Linux")
        k("Versión instalada", LinuxRuntimeInstaller.installedVersion(context))
        k("Entorno listo", LinuxRuntime.isInstalled(context))
        k("Raíz", LinuxRuntime.rootDir(context).path)

        h("Ajustes activos")
        k("Sobrescribir núcleos cliente", SessionPrefs.clientCpusOverride(context))
        k("  núcleos del cliente", CpuCores.listOrAll(SessionPrefs.clientCpus(context)))
        k("  núcleos de juegos", CpuCores.restrictionOrEmpty(SessionPrefs.gameCpus(context)).ifEmpty { "todos" })
        k("Turnip sysmem", SessionPrefs.tuSysmem(context))
        k("Zink lazy descriptors", SessionPrefs.zinkLazy(context))
        k("GL multihilo", SessionPrefs.glThread(context))
        k("Sin comprobación GL", SessionPrefs.noGlError(context))
        k("Modo Steam Deck", SessionPrefs.steamDeckMode(context))
        k("Perfil FEX", SessionPrefs.fexPreset(context).ifEmpty { "predeterminado" })
        k("Omitir xalia", SessionPrefs.noXalia(context))
        k("proot sin seccomp", SessionPrefs.prootNoSeccomp(context))
        k("Host del guest", SessionPrefs.guestHostname(context))
        k("DirectAudio para juegos", SessionPrefs.directAudio(context))
        k("Llenar pantalla", SessionPrefs.forceFullscreen(context))
        k("Audio del cliente", if (SessionPrefs.clientDirectAudio(context)) "DirectAudio" else "clásico")
        k("Micrófono", SessionPrefs.micEnabled(context))
        k("Controles en pantalla", SessionPrefs.oscMode(context))
        k("Modo táctil", SessionPrefs.touchMode(context))
        k("HUD de rendimiento", SessionPrefs.hudEnabled(context))
        k("Perfil de rendimiento", SessionPrefs.performanceProfile(context))
        k("Objetivo FPS", mobilePlan.targetFps.takeIf { it > 0 } ?: "panel")
        k("Modo de memoria baja", mobilePlan.lowMemory)
        k("Almacenamiento de juegos", when (val g = SessionPrefs.gameStorage(context)) {
            "" -> "automático (" + (com.droiddeck.launcher.session.GameStorage.effective(context)?.path ?: "sin tarjeta") + ")"
            SessionPrefs.GAME_STORAGE_OFF -> "solo interno"
            else -> g
        })

        h("Límites de procesos Android")
        k("Monitor de procesos fantasma", PhantomProcessLimit.reportValue(PhantomProcessLimit.read(context)))

        h("Archivos de ajuste en Descargas")
        for (name in listOf("droiddeck-env", "droiddeck-tu-debug", "droiddeck-driver",
                            "droiddeck-osc", "droiddeck-no-pad", "droiddeck-pad-log",
                            "droiddeck-no-hud")) {
            val f = File(Environment.getExternalStorageDirectory(), "Download/$name")
            if (f.isFile) k(name, FileUtils.readString(f)?.trim()?.replace('\n', ' ')?.ifEmpty { "(presente, vacío)" } ?: "(presente)")
        }

        b.append("\nEste informe no recopila números de serie, Android ID, identificadores publicitarios,\n")
        b.append("cuentas ni nombres de red. Se puede adjuntar directamente a un informe de error.\n")
        return b.toString()
    }

    private fun readSys(path: String): String? = FileUtils.readString(File(path))?.trim()?.ifEmpty { null }
}
