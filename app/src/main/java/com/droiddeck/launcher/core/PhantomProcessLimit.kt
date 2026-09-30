package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Android's phantom process monitor can kill the many child processes used by a Steam session. */
enum class PhantomProcessStatus {
    NOT_APPLICABLE,
    DISABLED,
    ENABLED,
    UNSET,
    UNREADABLE,
}

object PhantomProcessLimit {
    private const val SETTING = "settings_enable_monitor_phantom_procs"

    private const val MAX_PHANTOM = "2147483647"
    private const val PREFS = "phantom-process-limit"
    private const val PREF_ANDROID_12_OFF = "android12-off"

    fun usesDeviceConfig(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk == Build.VERSION_CODES.S

    fun shellCommands(enabled: Boolean, sdk: Int = Build.VERSION.SDK_INT): List<String> = when {
        usesDeviceConfig(sdk) && enabled -> listOf(
            "device_config delete activity_manager max_phantom_processes",
            "device_config set_sync_disabled_for_tests none",
        )
        usesDeviceConfig(sdk) -> listOf(
            "device_config set_sync_disabled_for_tests persistent",
            "device_config put activity_manager max_phantom_processes $MAX_PHANTOM",
        )
        else -> listOf("settings put global $SETTING ${if (enabled) "true" else "false"}")
    }

    fun verifyCommand(sdk: Int = Build.VERSION.SDK_INT): String =
        if (usesDeviceConfig(sdk)) "device_config get activity_manager max_phantom_processes"
        else "settings get global $SETTING"

    fun verified(output: String, enabled: Boolean, sdk: Int = Build.VERSION.SDK_INT): Boolean {
        val value = output.trim()
        return if (usesDeviceConfig(sdk)) (value == MAX_PHANTOM) != enabled
        else value == if (enabled) "true" else "false"
    }

    fun adbCommand(enabled: Boolean, sdk: Int = Build.VERSION.SDK_INT): String =
        shellCommands(enabled, sdk).joinToString(" && ") { "adb shell $it" }

    fun adbCommand(): String = adbCommand(false)

    fun rememberAndroid12(context: Context, off: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ANDROID_12_OFF, off).apply()
    }

    fun read(context: Context, sdk: Int = Build.VERSION.SDK_INT): PhantomProcessStatus {
        if (sdk < Build.VERSION_CODES.S) return PhantomProcessStatus.NOT_APPLICABLE
        if (usesDeviceConfig(sdk)) {
            val off = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ANDROID_12_OFF, false)
            return if (off) PhantomProcessStatus.DISABLED else PhantomProcessStatus.UNREADABLE
        }
        val global = try {
            Settings.Global.getString(context.contentResolver, SETTING)
        } catch (_: Exception) {
            return PhantomProcessStatus.UNREADABLE
        }
        return status(global, systemProperty(OVERRIDE_PROPERTY))
    }

    /**
     * The value Android itself acts on, in FeatureFlagUtils.isEnabled's order: the global setting
     * when it is set, otherwise the feature-flag override property. Developer options' "Disable
     * child process restrictions" writes only the property, so a device switched off there has no
     * global setting at all and must not be reported as unset.
     */
    fun status(global: String?, override: String?): PhantomProcessStatus {
        val value = global?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: override?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return when (value) {
            "false", "0" -> PhantomProcessStatus.DISABLED
            null -> PhantomProcessStatus.UNSET
            else -> PhantomProcessStatus.ENABLED
        }
    }

    private const val OVERRIDE_PROPERTY = "persist.sys.fflag.override.$SETTING"

    private fun systemProperty(name: String): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as? String
    } catch (_: Exception) {
        null
    }

    fun hasDeveloperToggle(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun blocksSteam(status: PhantomProcessStatus): Boolean =
        status != PhantomProcessStatus.NOT_APPLICABLE && status != PhantomProcessStatus.DISABLED

    fun title(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED -> "El límite de procesos secundarios está activado"
        PhantomProcessStatus.UNSET -> "El límite de procesos secundarios no está definido"
        PhantomProcessStatus.UNREADABLE -> "No se pudo comprobar el límite de procesos secundarios"
        PhantomProcessStatus.DISABLED -> "El límite de procesos secundarios está desactivado"
        PhantomProcessStatus.NOT_APPLICABLE -> "No se necesita limitar procesos secundarios"
    }

    fun instructions(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED, PhantomProcessStatus.UNSET, PhantomProcessStatus.UNREADABLE -> fixSentence() + " Si no es posible, conecta el dispositivo a un ordenador con ADB y ejecuta el comando de abajo. Vuelve aquí y comprueba de nuevo."
        PhantomProcessStatus.DISABLED -> "Las sesiones de Steam pueden iniciarse."
        PhantomProcessStatus.NOT_APPLICABLE -> "Esta versión de Android no usa este límite."
    }

    fun gateInstructions(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED, PhantomProcessStatus.UNSET, PhantomProcessStatus.UNREADABLE -> fixSentence()
        else -> instructions(status)
    }

    fun fixSentence(sdk: Int = Build.VERSION.SDK_INT): String =
        if (hasDeveloperToggle(sdk)) "Activa “Desactivar restricciones de procesos secundarios” en Opciones de desarrollador y mantén esas opciones activadas después."
        else "Esta versión de Android no tiene ese interruptor. Usa Depuración inalámbrica para cambiarlo desde este dispositivo."

    fun reportValue(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.DISABLED -> "desactivado (correcto: el sistema no finalizará los procesos secundarios de la sesión)"
        PhantomProcessStatus.ENABLED -> "activado (el sistema puede finalizar la sesión sin registro; desactiva Restringir procesos secundarios)"
        PhantomProcessStatus.UNSET -> "sin configurar (se aplica el valor de la ROM; DroidDeck necesita un valor desactivado explícito)"
        PhantomProcessStatus.UNREADABLE -> "no se puede leer (DroidDeck no pudo comprobar el ajuste)"
        PhantomProcessStatus.NOT_APPLICABLE -> "no aplicable antes de Android 12"
    }
}
