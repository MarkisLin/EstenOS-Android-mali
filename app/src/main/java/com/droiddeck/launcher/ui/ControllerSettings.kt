package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.session.SessionPrefs

class ControllerActions(
    val onOsc: (String) -> Unit,
    val onTint: (Int) -> Unit,
    val onOpacity: (Int) -> Unit,
    val onSize: (Int) -> Unit,
    val onStickClick: (Boolean) -> Unit,
    val onAdaptiveSticks: (Boolean) -> Unit,
    val onEditLayout: () -> Unit,
    val onResetLayout: () -> Unit,
    val onMapping: () -> Unit,
    val onResetAll: () -> Unit,
)

@Composable
private fun Swatch(color: Int) {
    Box(Modifier.size(14.dp).background(Color(color), CircleShape).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape))
}

@Composable
fun ColumnScope.ControllerRows(host: MenuHost, oscMode: String, c: ControllerPrefs.Settings, a: ControllerActions) {
    ChoiceRow(
        host, "controller-osc", "Controles en pantalla", "Cuándo aparece el panel táctil en una sesión",
        listOf(SessionPrefs.OSC_AUTO to "Automático", SessionPrefs.OSC_ALWAYS to "Siempre", SessionPrefs.OSC_STEAM_QAM to "Steam + acceso rápido", SessionPrefs.OSC_NEVER to "Nunca"),
        oscMode, note = "Automático muestra todos los controles sin mando. Steam + acceso rápido muestra solo esos botones.", onPick = a.onOsc,
    )
    val tintOpen = host.open == "controller-tint"
    SettingsRow("Color", "Tinte de los botones en pantalla", highlighted = tintOpen) {
        Box {
            ValueChip(ControllerPrefs.tints.firstOrNull { it.first == c.tint }?.second ?: "Personalizado", tintOpen) {
                host.open = if (tintOpen) null else "controller-tint"
            }
            AnchoredMenu(tintOpen, onDismiss = { if (host.open == "controller-tint") host.open = null }, title = "Color") { firstItemFocus ->
                ControllerPrefs.tints.forEachIndexed { index, (color, name) ->
                    MenuItem(name, checked = c.tint == color, leading = { Swatch(color) }, focusRequester = if (index == 0) firstItemFocus else null) {
                        a.onTint(color)
                        host.open = null
                    }
                }
            }
        }
    }
    ChoiceRow(host, "controller-opacity", "Opacidad", null, ControllerPrefs.opacities.map { it to "$it%" }, c.opacity, onPick = a.onOpacity)
    ChoiceRow(host, "controller-size", "Tamaño de botones", "100% mantiene el tamaño estándar", ControllerPrefs.sizes.map { it to "$it%" }, c.size, onPick = a.onSize)
    ToggleRow(host, "controller-stick-click", "Clic del stick", "Toca dos veces un stick y mantén para L3 o R3", c.stickClick, onChange = a.onStickClick)
    ToggleRow(host, "controller-adaptive", "Sticks adaptativos", "Los sticks aparecen al tocar cerca de su posición y se ocultan al soltar", c.adaptiveSticks, onChange = a.onAdaptiveSticks)
    SettingsRow("Distribución", if (c.customLayout) "Posiciones personalizadas guardadas" else "Colocados según el tamaño de pantalla y tu agarre") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton("Editar") { a.onEditLayout() }
            SecondaryButton("Restablecer", enabled = c.customLayout) { a.onResetLayout() }
        }
    }
    val remapped = c.mapping.count { (id, target) -> id != target }
    ActionRow("Asignación de botones", if (remapped == 0) "Cada botón envía su entrada original" else "$remapped de ${c.mapping.size} botones reasignados", "Configurar", a.onMapping)
    ActionRow("Restablecer control", "Restaura color, opacidad, tamaño, comportamiento de sticks, asignación y distribución", "Restablecer", a.onResetAll)
}

@Composable
fun ControllerMappingPage(mapping: Map<String, String>, onPick: (String, String) -> Unit, onReset: () -> Unit, onBack: () -> Unit) {
    val host = rememberMenuHost()
    SettingsPage(
        host, title = "Asignación de botones", eyebrow = "Control",
        lede = "Elige qué entrada envía cada botón al juego. Oculto elimina el botón.",
        onBack = onBack,
    ) {
        SettingsGroup("Botones en pantalla") {
            for ((id, name) in ControllerPrefs.mappable) {
                ChoiceRow(host, "map-$id", name, null, ControllerPrefs.targets, mapping[id] ?: id) { onPick(id, it) }
            }
        }
        SettingsGroup("Predeterminados") {
            ActionRow("Restablecer asignación", "Cada botón vuelve a enviar su entrada original", "Restablecer", onReset)
        }
    }
}
