package com.droiddeck.launcher.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Mobile-first policy for Steam on phones.
 *
 * DroidDeck Mali-first deliberately targets devices sold with at least 4 GB of RAM.  The normal
 * automatic path protects frame time and memory before sacrificing image quality: 720p is kept as
 * the baseline even on the 4 GB tier, while the FPS target and allocator/process overhead are
 * reduced first.  Higher tiers can move to 45/60 FPS and users may opt into 900p manually.
 *
 * Android's MemoryInfo.totalMem is the kernel-visible amount, not the number printed on the box;
 * several nominal 4 GB phones expose roughly 3.4-3.8 GB after reserved regions.  The support gate
 * therefore uses a conservative kernel-visible floor that still cleanly excludes 2/3 GB devices.
 */
object MobilePerformance {
    const val AUTO = "auto"
    const val SAVER = "saver"
    const val BALANCED = "balanced"
    const val PERFORMANCE = "performance"
    const val QUALITY = "quality"

    const val MINIMUM_NOMINAL_RAM_GB = 4

    data class Plan(
        val id: String,
        val label: String,
        /** Maximum render height used when the user left resolution on Automatic. */
        val maxHeight: Int,
        /** gamescope refresh/FPS cap. 0 means use the panel/session refresh. */
        val targetFps: Int,
        /** Reduce glibc/process overhead for the smallest supported devices. */
        val lowMemory: Boolean,
        val reason: String,
    )

    // Nominal 4 GB phones commonly expose less than 4 GiB through /proc/meminfo because firmware,
    // modem/GPU and contiguous heaps reserve part of physical RAM. 3.4 GB remains well above the
    // normal kernel-visible amount of a 3 GB device, so it is a practical support floor.
    private const val MIN_SUPPORTED_VISIBLE_BYTES = 3_400_000_000L

    // Rough visible-RAM boundaries used only to choose conservative launch defaults. They do not
    // claim that RAM measures GPU speed; that is why Automatic never selects the 900p profile.
    private const val FOUR_GB_TIER_MAX = 5_000_000_000L
    private const val EIGHT_GB_TIER_MIN = 6_800_000_000L

    fun plan(context: Context, selected: String = AUTO): Plan = when (selected) {
        SAVER -> saver("seleccionado manualmente")
        BALANCED -> balanced("seleccionado manualmente")
        PERFORMANCE -> performance("seleccionado manualmente")
        QUALITY -> quality("seleccionado manualmente")
        else -> automatic(context)
    }

    fun automatic(context: Context): Plan {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val total = info.totalMem
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
            }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else PowerManager.THERMAL_STATUS_NONE

        return automaticForSignals(total, am.isLowRamDevice, thermal)
    }

    /** Pure policy entry point kept testable without Android services. */
    fun automaticForSignals(totalRam: Long, lowRamDevice: Boolean, thermalStatus: Int): Plan {
        // Severe heat wins over every memory tier. We keep 720p and reduce temporal load first.
        if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            return saver("el teléfono ya está caliente al iniciar la sesión")
        }

        // 4 GB is supported, not treated as an unsupported ultra-low-end target. The smallest
        // supported tier stays at 720p/30 and trims allocator/mangoapp overhead instead of 540p.
        if (lowRamDevice || (totalRam in 1 until FOUR_GB_TIER_MAX)) {
            return saver(
                if (lowRamDevice) "Android marca el dispositivo como memoria limitada"
                else "perfil de 4 GB: se conserva 720p y se limita a 30 FPS",
            )
        }

        // Moderate thermal pressure is a good reason not to ask for 60 FPS even on a large-memory
        // phone. 45 FPS preserves noticeably more motion than 30 while cutting GPU/CPU work.
        if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            return balanced("temperatura elevada: 720p con objetivo de 45 FPS")
        }

        // Only the roomy tier automatically asks for 60 FPS. RAM is merely a headroom signal here;
        // the 900p Quality mode remains manual because sustained GPU throughput cannot be inferred
        // reliably from Android model names or RAM capacity.
        if (totalRam >= EIGHT_GB_TIER_MIN) {
            return performance("margen de memoria suficiente para priorizar 720p / 60 FPS")
        }

        return balanced(
            if (totalRam > 0) "perfil móvil equilibrado: 720p / 45 FPS"
            else "perfil móvil conservador: 720p / 45 FPS",
        )
    }

    private fun saver(reason: String) = Plan(
        SAVER, "Ahorro", maxHeight = 720, targetFps = 30, lowMemory = true, reason = reason,
    )

    private fun balanced(reason: String) = Plan(
        BALANCED, "Equilibrado", maxHeight = 720, targetFps = 45, lowMemory = false, reason = reason,
    )

    private fun performance(reason: String) = Plan(
        PERFORMANCE, "Fluido", maxHeight = 720, targetFps = 60, lowMemory = false, reason = reason,
    )

    private fun quality(reason: String) = Plan(
        QUALITY, "Calidad", maxHeight = 900, targetFps = 0, lowMemory = false, reason = reason,
    )

    fun totalRamBytes(context: Context): Long = runCatching {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(0L)

    /** Null when the device satisfies the project's 4 GB minimum or Android could not report RAM. */
    fun minimumRamIssue(context: Context): String? = minimumRamIssue(totalRamBytes(context))

    fun minimumRamIssue(totalRam: Long): String? {
        if (totalRam <= 0L || totalRam >= MIN_SUPPORTED_VISIBLE_BYTES) return null
        val gb = totalRam / 1_000_000_000.0
        return "DroidDeck Mali-first requiere un teléfono con al menos 4 GB de RAM. " +
            "Android reporta %.1f GB utilizables en este dispositivo; los equipos de 2–3 GB no forman parte del objetivo de esta versión.".format(gb)
    }

    fun supportsMinimumRam(context: Context): Boolean = minimumRamIssue(context) == null

    fun summary(context: Context, selected: String = AUTO): String {
        val p = plan(context, selected)
        val fps = if (p.targetFps > 0) "${p.targetFps} FPS" else "FPS del panel"
        return if (selected == AUTO) "Automático → ${p.label} · ${p.maxHeight}p · $fps" else "${p.label} · ${p.maxHeight}p · $fps"
    }
}
