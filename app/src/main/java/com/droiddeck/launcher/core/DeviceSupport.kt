package com.droiddeck.launcher.core

import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.droiddeck.launcher.gpu.MaliKbaseProbe
import com.droiddeck.launcher.gpu.MaliKbaseProfiles
import java.io.File

/**
 * GPU/backend detection for DroidDeck.
 *
 * Upstream is built around Qualcomm's KGSL + Turnip path. This fork keeps that path for Adreno,
 * but treats ARM Mali as a first-class target and deliberately selects Android's system Vulkan
 * driver for the host compositor there. The Linux guest driver is selected separately.
 */
object DeviceSupport {
    enum class GpuFamily { ADRENO, MALI, XCLIPSE, POWERVR, UNKNOWN }
    enum class HostVulkanBackend { ADRENO_TURNIP, SYSTEM_VULKAN }

    /** GPU interface that the glibc guest can actually drive. Host Vulkan is a separate choice. */
    enum class GuestGpuBackend {
        ADRENO_KGSL,
        /** Mainline/custom kernels exposing a render node usable by PanVK (Panfrost/Panthor). */
        MALI_DRM,
        /** Stock ARM/MediaTek/Samsung mali_kbase ABI (/dev/mali0), probed for JM/CSF + UAPI. */
        MALI_KBASE,
        NONE
    }

    private val familyValue: GpuFamily by lazy { detectFamily() }

    @JvmStatic
    fun family(): GpuFamily = familyValue

    @JvmStatic
    fun adreno(): Boolean = family() == GpuFamily.ADRENO

    @JvmStatic
    fun mali(): Boolean = family() == GpuFamily.MALI

    /** Mali is the primary target of this fork; Adreno remains as the upstream compatibility path. */
    @JvmStatic
    fun supportedForThisBuild(): Boolean = when (family()) {
        GpuFamily.MALI, GpuFamily.ADRENO -> true
        else -> false
    }

    /** Which Vulkan stack the Android compositor should load. */
    @JvmStatic
    fun hostVulkanBackend(): HostVulkanBackend = when (family()) {
        GpuFamily.ADRENO -> HostVulkanBackend.ADRENO_TURNIP
        else -> HostVulkanBackend.SYSTEM_VULKAN
    }

    /**
     * Linux guest GPU ABI. Do not confuse this with [hostVulkanBackend]: a stock Mali phone can
     * render the Android compositor perfectly while exposing Kbase rather than Linux DRM to PanVK.
     */
    @JvmStatic
    fun guestGpuBackend(): GuestGpuBackend = when (family()) {
        GpuFamily.ADRENO -> if (File("/dev/kgsl-3d0").exists()) GuestGpuBackend.ADRENO_KGSL else GuestGpuBackend.NONE
        GpuFamily.MALI -> {
            if (usableDrmRenderNode() != null) GuestGpuBackend.MALI_DRM
            else if (MaliKbaseProbe.usable()) GuestGpuBackend.MALI_KBASE
            else GuestGpuBackend.NONE
        }
        else -> GuestGpuBackend.NONE
    }

    /** First DRM render node this app can really open, not merely stat through Android /dev. */
    @JvmStatic
    fun usableDrmRenderNode(): String? {
        val dir = File("/dev/dri")
        val nodes = runCatching {
            dir.listFiles()?.filter { it.name.startsWith("renderD") }?.sortedBy { it.name } ?: emptyList()
        }.getOrDefault(emptyList())
        for (node in nodes) {
            val fd = runCatching { Os.open(node.path, OsConstants.O_RDWR or OsConstants.O_CLOEXEC, 0) }.getOrNull()
            if (fd != null) {
                runCatching { Os.close(fd) }
                return node.path
            }
        }
        return null
    }

    @JvmStatic
    fun supportSummary(): String = when (family()) {
        GpuFamily.MALI -> when (guestGpuBackend()) {
            GuestGpuBackend.MALI_DRM -> "ARM Mali detectada · Vulkan del sistema en host + ruta DRM compatible con PanVK en guest"
            GuestGpuBackend.MALI_KBASE -> {
                val probe = MaliKbaseProbe.probe()
                "ARM Mali detectada · Vulkan del sistema en host · ${probe.shortSummary()} · ${MaliKbaseProfiles.readiness(probe)}"
            }
            else -> "ARM Mali detectada · Vulkan del sistema en host; no hay un nodo GPU accesible para el guest"
        }
        GpuFamily.ADRENO -> "Adreno detectada · ruta heredada Turnip/KGSL habilitada"
        GpuFamily.XCLIPSE -> "Xclipse detectada · todavía sin backend GPU para el guest"
        GpuFamily.POWERVR -> "PowerVR detectada · todavía sin backend GPU para el guest"
        GpuFamily.UNKNOWN -> "Familia de GPU no identificada · solo fallback Vulkan del sistema"
    }

    /** The SoC/hardware name Android exposes, useful next to the GPU-family probe. */
    @JvmStatic
    fun gpuName(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { "esta GPU" }
    }

    private fun detectFamily(): GpuFamily {
        // Qualcomm: KGSL is specific enough that it wins over all filename heuristics.
        if (existsAny(
                "/sys/class/kgsl/kgsl-3d0",
                "/dev/kgsl-3d0",
                "/vendor/lib64/hw/vulkan.adreno.so",
                "/vendor/lib/hw/vulkan.adreno.so",
                "/system/vendor/lib64/hw/vulkan.adreno.so",
                "/system/vendor/lib/hw/vulkan.adreno.so",
            )) return GpuFamily.ADRENO

        // ARM's proprietary Android stack normally exposes mali0 and/or a vulkan.mali/libGLES_mali
        // module. Immortalis parts use the same Mali userspace naming, so they intentionally land
        // here too.
        if (existsAny(
                "/dev/mali0",
                "/sys/class/misc/mali0",
                "/vendor/lib64/hw/vulkan.mali.so",
                "/vendor/lib/hw/vulkan.mali.so",
                "/system/vendor/lib64/hw/vulkan.mali.so",
                "/system/vendor/lib/hw/vulkan.mali.so",
                "/vendor/lib64/egl/libGLES_mali.so",
                "/vendor/lib/egl/libGLES_mali.so",
                "/system/vendor/lib64/egl/libGLES_mali.so",
                "/system/vendor/lib/egl/libGLES_mali.so",
            ) || hwModuleNameContains("mali")) return GpuFamily.MALI

        if (hwModuleNameContains("powervr") || hwModuleNameContains("rogue")) return GpuFamily.POWERVR

        // Samsung's Xclipse Vulkan HAL names vary by generation/OEM. Keep the probe conservative:
        // only classify when the module itself says xclipse; otherwise UNKNOWN is safer than
        // accidentally taking the wrong backend.
        if (hwModuleNameContains("xclipse")) return GpuFamily.XCLIPSE

        return GpuFamily.UNKNOWN
    }

    private fun existsAny(vararg paths: String): Boolean = paths.any { File(it).exists() }

    private fun hwModuleNameContains(needle: String): Boolean {
        val n = needle.lowercase()
        val dirs = arrayOf(
            File("/vendor/lib64/hw"), File("/vendor/lib/hw"),
            File("/system/vendor/lib64/hw"), File("/system/vendor/lib/hw"),
            File("/vendor/lib64/egl"), File("/vendor/lib/egl"),
        )
        return dirs.any { dir ->
            runCatching { dir.listFiles()?.any { it.name.lowercase().contains(n) } == true }.getOrDefault(false)
        }
    }
}
