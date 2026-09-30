package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ProtonRow(val id: String, val name: String, val installed: String?, val queued: Boolean)

@Composable
fun ProtonPage(
    rows: List<ProtonRow>,
    busyId: String?,
    stage: String?,
    percent: Int,
    runtimeReady: Boolean,
    sessionRunning: Boolean,
    onInstall: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    val host = rememberMenuHost()
    val colors = MaterialTheme.colorScheme
    SettingsPage(
        host,
        title = "Versiones de Proton",
        eyebrow = "Ajustes",
        lede = "EstenOS instala automáticamente un Proton ARM64 base al preparar Steam. GE-Proton y proton-cachyos son alternativas opcionales para juegos que necesiten otra compatibilidad.",
        onBack = onBack,
    ) {
        Text(
            "No necesitas instalar ninguno de los dos para empezar. Si un juego funciona con el Proton automático, déjalo así; instala una alternativa solo cuando quieras probar compatibilidad distinta.",
            fontSize = 12.sp,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        if (!runtimeReady) Text(
            "Instala el entorno Linux desde Ajustes antes de administrar herramientas de compatibilidad.",
            fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        if (sessionRunning) Text(
            "Detén la sesión activa antes de instalar o eliminar una herramienta de compatibilidad.",
            fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        SettingsGroup("Compilaciones disponibles") {
            for (row in rows) {
                SettingsRow(
                    row.name,
                    when {
                        row.installed != null -> "Instalado ${row.installed}"
                        row.queued -> "Pendiente de una solicitud anterior"
                        else -> "No instalado"
                    },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when {
                            row.installed != null -> SecondaryButton("Eliminar", enabled = busyId == null && runtimeReady && !sessionRunning) { onRemove(row.id) }
                            busyId != null -> SecondaryButton(if (busyId == row.id) "Instalando…" else "Instalar", enabled = false) {}
                            else -> SecondaryButton("Instalar ahora", enabled = runtimeReady && !sessionRunning) { onInstall(row.id) }
                        }
                        if (row.queued && row.installed == null && busyId == null) {
                            SecondaryButton("Cancelar pendiente", enabled = !sessionRunning) { onCancel(row.id) }
                        }
                    }
                }
                if (busyId == row.id) Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Text(
                        stage ?: "Preparando instalación…",
                        fontSize = 13.sp,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    Text(
                        if (percent >= 0) "$percent% completado" else "Trabajando…",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(7.dp))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(7.dp))
                    Text(
                        "Puedes cambiar de app o apagar la pantalla; EstenOS continuará en segundo plano.",
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
