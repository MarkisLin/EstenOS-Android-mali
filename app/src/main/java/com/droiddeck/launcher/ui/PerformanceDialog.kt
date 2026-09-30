package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.core.MobilePerformance
import com.droiddeck.launcher.core.DeviceSupport

class CoreRow(val core: Int, val label: String)

@Composable
fun PerformancePage(
    cores: List<CoreRow>,
    performanceProfile: String,
    performanceSummary: String,
    clientOverride: Boolean,
    clientCores: Set<Int>,
    gameCores: Set<Int>,
    tuSysmem: Boolean,
    zinkLazy: Boolean,
    glThread: Boolean,
    noGlError: Boolean,
    noXalia: Boolean,
    prootNoSeccomp: Boolean,
    guestHostname: String,
    phantomWarning: String?,
    onPerformanceProfile: (String) -> Unit,
    onClientOverride: (Boolean) -> Unit,
    onTuSysmem: (Boolean) -> Unit,
    onZinkLazy: (Boolean) -> Unit,
    onGlThread: (Boolean) -> Unit,
    onNoGlError: (Boolean) -> Unit,
    onNoXalia: (Boolean) -> Unit,
    onProotNoSeccomp: (Boolean) -> Unit,
    onGuestHostname: (String) -> Unit,
    onClientCore: (Int, Boolean) -> Unit,
    onGameCore: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val host = rememberMenuHost()
    val colors = MaterialTheme.colorScheme
    val coreItems = cores.map { it.core to it.label }
    var advanced by remember { mutableStateOf(false) }
    val showTurnip = DeviceSupport.family() == DeviceSupport.GpuFamily.ADRENO
    SettingsPage(
        host, title = "Rendimiento",
        lede = "Elige cómo equilibrar fluidez, temperatura y batería. Automático funciona sin tocar nada más.",
        onBack = onDismiss,
    ) {
        SettingsGroup("Perfil para móviles") {
            ChoiceRow(
                host, "mobile-profile", "Rendimiento",
                performanceSummary + ". Automático es la opción recomendada y prioriza estabilidad térmica y memoria.",
                listOf(
                    MobilePerformance.AUTO to "Automático (recomendado)",
                    MobilePerformance.SAVER to "Ahorro · 720p / 30 FPS",
                    MobilePerformance.BALANCED to "Equilibrado · 720p / 45 FPS",
                    MobilePerformance.PERFORMANCE to "Fluido · 720p / 60 FPS",
                    MobilePerformance.QUALITY to "Calidad · hasta 900p",
                ),
                performanceProfile,
                onPick = onPerformanceProfile,
            )
        }
        SettingsGroup("Opciones avanzadas") {
            ToggleRow(
                host, "advanced", "Mostrar ajustes técnicos",
                "Solo hacen falta para pruebas o para solucionar un problema concreto.",
                advanced, onChange = { advanced = it },
            )
        }
        if (advanced) {
            SettingsGroup("Núcleos del cliente de Steam") {
                ToggleRow(
                    host, "override", "Reemplazar la selección de núcleos de Steam",
                    "Fija Steam, su interfaz y gamescope a los núcleos elegidos. Se reaplica cada pocos segundos.",
                    clientOverride, onChange = onClientOverride,
                )
                MultiRow(
                    host, "clientCores", "Núcleos del cliente", if (clientOverride) "Elige núcleos para Steam." else "Activa la opción anterior para elegir.",
                    coreItems, clientCores, enabled = clientOverride, onToggle = onClientCore,
                )
            }
            SettingsGroup("Núcleos de los juegos") {
                MultiRow(
                    host, "gameCores", "Núcleos de los juegos",
                    "Elige núcleos para los juegos. Seleccionarlos todos permite que Android use cualquier núcleo.",
                    coreItems, gameCores, onToggle = onGameCore,
                )
            }
            SettingsGroup("Interfaz del cliente") {
                ToggleRow(
                    host, "glthread", "GL en hilos",
                    "Puede mejorar la respuesta de los menús de Steam. El impacto depende del dispositivo.",
                    glThread, onChange = onGlThread,
                )
                ToggleRow(
                    host, "zink", "Zink: descriptores diferidos",
                    "Recomendado para drivers sin búferes de descriptores.",
                    zinkLazy, onChange = onZinkLazy,
                )
                ToggleRow(
                    host, "noglerror", "Omitir comprobaciones de errores GL",
                    "Desactiva la validación GL en cada llamada.",
                    noGlError, onChange = onNoGlError,
                )
            }
            SettingsGroup("Correcciones de sesión") {
                if (showTurnip) {
                    ToggleRow(
                        host, "sysmem", "Turnip: renderizado sysmem",
                        "Necesario en algunos Adreno. Puede corregir errores gráficos, aunque también reducir el rendimiento.",
                        tuSysmem, onChange = onTuSysmem,
                    )
                }
                ToggleRow(
                    host, "xalia", "Omitir el ayudante xalia de Steam",
                    "Desactiva el ayudante de navegación con control de Proton. Pruébalo si la sesión falla al iniciar.",
                    noXalia, onChange = onNoXalia,
                )
                ToggleRow(
                    host, "seccomp", "Ejecutar proot sin seccomp",
                    "Puede corregir errores de llamadas al sistema en algunos kernels, pero puede reducir el rendimiento.",
                    prootNoSeccomp, onChange = onProotNoSeccomp,
                )
            }
            SettingsGroup("Identidad de la sesión") {
                var draft by remember(guestHostname) { mutableStateOf(guestHostname) }
                val valid = SessionPrefs.validGuestHostname(draft) != null
                SettingsRow(
                    "Nombre del equipo",
                    if (valid || draft.isBlank()) "Nombre que la sesión y Steam muestran para este equipo. Déjalo vacío para restaurar ${SessionPrefs.DEFAULT_GUEST_HOSTNAME}."
                    else "Solo letras, números y guiones internos, hasta 63 caracteres. No se guarda hasta que sea válido.",
                ) {
                    OutlinedTextField(
                        draft, { v ->
                            draft = v.take(63)
                            if (draft.isBlank() || SessionPrefs.validGuestHostname(draft) != null) onGuestHostname(draft)
                        },
                        singleLine = true, isError = !valid && draft.isNotBlank(),
                        placeholder = { Text(SessionPrefs.DEFAULT_GUEST_HOSTNAME) },
                        modifier = Modifier.width(220.dp),
                    )
                }
            }
        }
        if (phantomWarning != null) {
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.error.copy(alpha = 0.08f))
                    .border(1.dp, colors.error.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                Text("Android está configurado para cerrar esta sesión", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
                Text(phantomWarning, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
