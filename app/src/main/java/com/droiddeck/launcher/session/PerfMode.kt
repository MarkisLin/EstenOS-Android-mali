package com.droiddeck.launcher.session

import android.app.Activity
import android.app.GameManager
import android.app.GameState
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.droiddeck.launcher.core.MobilePerformance

/**
 * What a session tells Android about itself so the SoC is run for a game, not for an app.
 *
 * The manifest already makes the app a game (appCategory + game_mode_config); this is the
 * per-window half, applied when the session activity is created:
 *
 *  - sustained performance mode (Android 7+): a clock floor that does not throttle away after
 *    two minutes, which suits an hour in Big Picture better than a boost that fades;
 *  - a refresh rate matched to the mobile profile (Android 6+): Ahorro/Equilibrado avoid wasting
 *    power at 90/120/144 Hz while Calidad may use the fastest panel mode;
 *  - GameManager's game state (Android 13+): "in gameplay", so an OEM framework that releases
 *    its boost on loading screens or menus keeps it up.
 *
 * Every step is version-guarded and skipped silently below its API; the returned line is what
 * the session log shows, which is the first thing to read on a device that feels slow.
 */
object PerfMode {
    private const val TAG = "PerfMode"

    fun apply(a: Activity): String {
        val parts = ArrayList<String>(3)

        // Sustained performance mode.
        parts += if (Build.VERSION.SDK_INT >= 24) {
            val pm = a.getSystemService(PowerManager::class.java)
            if (pm?.isSustainedPerformanceModeSupported == true) {
                try { a.window.setSustainedPerformanceMode(true); "rendimiento sostenido activo" } catch (t: Throwable) { Log.w(TAG, "sustained mode", t); "rendimiento sostenido rechazado" }
            } else "rendimiento sostenido no disponible"
        } else "rendimiento sostenido requiere Android 7"

        // A 120/144 Hz panel is useful in Calidad, but wasteful when the session itself is capped
        // to 30/60.  Saver/Balanced prefer the best mode at or below ~60 Hz; if the panel exposes
        // only higher rates, choose the lowest one rather than forcing the maximum.
        parts += try {
            val display = if (Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay
            if (display == null || Build.VERSION.SDK_INT < 23) "frecuencia de pantalla sin cambios" else {
                val cur = display.mode
                val plan = MobilePerformance.plan(a, SessionPrefs.performanceProfile(a))
                val modes = display.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                val best = if (plan.id == MobilePerformance.QUALITY) {
                    modes.maxByOrNull { it.refreshRate }
                } else {
                    modes.filter { it.refreshRate <= 60.5f }.maxByOrNull { it.refreshRate }
                        ?: modes.minByOrNull { it.refreshRate }
                }
                if (best != null && best.modeId != cur.modeId && kotlin.math.abs(best.refreshRate - cur.refreshRate) > 0.5f) {
                    a.window.attributes = a.window.attributes.apply { preferredDisplayModeId = best.modeId }
                    "pantalla ${cur.refreshRate.toInt()} → ${best.refreshRate.toInt()} Hz (${plan.label})"
                } else "pantalla ${cur.refreshRate.toInt()} Hz (${plan.label})"
            }
        } catch (t: Throwable) { Log.w(TAG, "display mode", t); "no se pudo ajustar la frecuencia" }

        // GameManager: what mode the OS put us in, and that we are playing.
        parts += if (Build.VERSION.SDK_INT >= 31) {
            val gm = a.getSystemService(GameManager::class.java)
            if (gm == null) "GameManager no disponible" else {
                val mode = when (gm.gameMode) {
                    GameManager.GAME_MODE_PERFORMANCE -> "rendimiento"
                    GameManager.GAME_MODE_BATTERY -> "ahorro"
                    GameManager.GAME_MODE_STANDARD -> "estándar"
                    else -> "no compatible"
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    try { gm.setGameState(GameState(false, GameState.MODE_GAMEPLAY_INTERRUPTIBLE)) } catch (t: Throwable) { Log.w(TAG, "game state", t) }
                }
                "modo de juego $mode"
            }
        } else "modo de juego requiere Android 12"

        return parts.joinToString(" · ")
    }
}
