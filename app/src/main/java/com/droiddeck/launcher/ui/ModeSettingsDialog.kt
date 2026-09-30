package com.droiddeck.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.FexPreset
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.runtime.DeckyManager
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService

/** [tag] is shown beside the name: [BUNDLED], [DOWNLOADED], [IMPORTED], or "" for Auto / Runtime default. */
class DriverRow(val id: String, val name: String, val detail: String, val removable: Boolean, val tag: String = "") {
    companion object {
        const val BUNDLED = "INCLUIDO"
        const val DOWNLOADED = "DESCARGADO"
        const val IMPORTED = "IMPORTADO"
    }
}

/** A release driver (Banners-Turnip, WinNative) that is not installed yet; [key] is its asset name. */
class DownloadRow(val key: String, val label: String, val detail: String, val progress: Int? = null)

class ModeSettings(
    val mode: String,
    val resolutionCap: Int,
    /** A fixed session size, or null for the cap and shape. */
    val customResolution: Pair<Int, Int>? = null,
    val shapeMode: String,
    val hdr: Boolean,
    val hdrReason: String?,
    val linuxRows: List<DriverRow>,
    val linuxSelected: String,
    val androidRows: List<DriverRow>,
    val androidSelected: String,
    val touchMode: String,
    val suspendPolicy: String,
    /** Steam only. */
    val oscMode: String?,
    /** Steam only: whether single and double Back actions are swapped. */
    val backActionsInverted: Boolean = false,
    val directAudio: Boolean?,
    val clientDirectAudio: Boolean = false,
    val mic: Boolean?,
    val gameStorage: String? = null,
    val storageOptions: List<Pair<String, String>> = emptyList(),
    val fexPreset: String? = null,
    /** Steam only: games are stretched to fill the screen (null = not a Steam page). */
    val forceFullscreen: Boolean? = null,
    /** Steam only: the client branch forced on the command line. */
    val steamChannel: String? = null,
    /** Steam only: enable the SteamOS client interface and its performance controls. */
    val steamDeckMode: Boolean = false,
    /** Steam only: start a Steam session when DroidDeck opens. */
    val runSteamAtStartup: Boolean = false,
    /** Steam only: the user's chosen Games folders; null outside Steam. */
    val addedGamesDirs: List<String>? = null,
    val addedGames: List<AddedGameRow> = emptyList(),
    val addedGamesArt: Boolean = true,
    /** Latest Banners-Turnip release: what each driver menu offers to download, and the refresh line. */
    val linuxDownloads: List<DownloadRow> = emptyList(),
    val androidDownloads: List<DownloadRow> = emptyList(),
    val releaseStatus: String = "Aún no comprobado; pulsa actualizar para buscar controladores nuevos",
    val releaseChecking: Boolean = false,
    /** A bundled display driver was deleted: the page offers to restore it. */
    val canRestoreBundled: Boolean = false,
    /** Steam only: Decky Loader is managed from the Steam session settings. */
    val deckyInstalled: String? = null,
    val deckyLatestRelease: DeckyManager.Release? = null,
    val deckyChecking: Boolean = false,
    val deckyStage: String? = null,
    val deckyPercent: Int = -1,
    val deckyEnabled: Boolean = false,
    val deckySessionRunning: Boolean = false,
)

/** One added game as the settings page shows it: its folder, the chosen .exe, the other .exe files it could be. */
class AddedGameRow(val folderPath: String, val folderName: String, val exePath: String, val exeName: String, val candidates: List<Pair<String, String>>)

class ModeSettingsActions(
    val onResolution: (Int) -> Unit,
    /** Null clears it. */
    val onCustomResolution: (Pair<Int, Int>?) -> Unit = {},
    val onShape: (String) -> Unit,
    val onHdr: (Boolean) -> Unit,
    val onSelectLinux: (String) -> Unit,
    val onImportLinux: () -> Unit,
    val onRemoveLinux: (String) -> Unit,
    val onRefreshReleases: () -> Unit = {},
    /** Asset name of the release driver to download. */
    val onDownloadDriver: (String) -> Unit = {},
    val onRestoreBundled: () -> Unit = {},
    val onSelectAndroid: (String) -> Unit,
    val onImportAndroid: () -> Unit,
    val onRemoveAndroid: (String) -> Unit,
    val onTouch: (String) -> Unit,
    val onSuspendPolicy: (String) -> Unit,
    val onOsc: (String) -> Unit,
    val onBackActionsInverted: (Boolean) -> Unit = {},
    val onDirectAudio: (Boolean) -> Unit,
    val onClientDirectAudio: (Boolean) -> Unit = {},
    val onMic: (Boolean) -> Unit,
    val onGameStorage: (path: String, label: String) -> Unit = { _, _ -> },
    val onPickGameStorageFolder: () -> Unit = {},
    val onFexPreset: (String) -> Unit = {},
    val onForceFullscreen: (Boolean) -> Unit = {},
    val onSteamChannel: (String) -> Unit = {},
    val onSteamDeckMode: (Boolean) -> Unit = {},
    val onRunSteamAtStartup: (Boolean) -> Unit = {},
    val onPickAddedGamesDir: () -> Unit = {},
    val onForgetAddedGamesDir: (path: String) -> Unit = {},
    val onAddedGamesArt: (Boolean) -> Unit = {},
    val onAddedGameExe: (folderPath: String, path: String) -> Unit = { _, _ -> },
    val onPickAddedGameExe: (folderPath: String) -> Unit = {},
    val onDeckyInstall: (DeckyManager.Release) -> Unit = {},
    val onDeckyCheck: () -> Unit = {},
    val onDeckyEnabled: (Boolean) -> Unit = {},
    val onDeckyUninstall: () -> Unit = {},
    val onDismiss: () -> Unit,
)

@Composable
fun ModeSettingsPage(s: ModeSettings, a: ModeSettingsActions) {
    val steam = s.mode == SessionService.MODE_STEAM
    val host = rememberMenuHost()
    var confirmDeckyRemoval by remember { mutableStateOf(false) }
    // The two driver lists open as full pages over this one ("rt" = runtime, "panel" = display).
    // Coming back restores this page as it was left: the same scroll position, and controller focus
    // on the driver box that opened the page.
    var driverPage by remember { mutableStateOf<String?>(null) }
    var returnTo by remember { mutableStateOf<String?>(null) }
    val pageScroll = androidx.compose.foundation.rememberScrollState()
    val runtimeChip = remember { androidx.compose.ui.focus.FocusRequester() }
    val displayChip = remember { androidx.compose.ui.focus.FocusRequester() }
    val firstChip = remember { androidx.compose.ui.focus.FocusRequester() }
    fun openDriverPage(key: String) { returnTo = key; driverPage = key }
    androidx.compose.runtime.LaunchedEffect(driverPage) {
        if (driverPage == null) {
            // One frame first: the box has to be laid out before it can take focus. Opened from the
            // cog, focus starts on the first control (Resolution) so the d-pad works at once; back
            // from a driver page, it returns to the driver box that opened it.
            androidx.compose.runtime.withFrameNanos { }
            val target = when (returnTo) { "rt" -> runtimeChip; "panel" -> displayChip; else -> firstChip }
            runCatching { target.requestFocus() }
        }
    }
    when (driverPage) {
        "rt" -> {
            DriverPage(
                title = "Controlador del entorno",
                hint = "Lo usan Steam y los juegos. Se aplica en la próxima sesión.",
                rows = s.linuxRows, selected = s.linuxSelected, downloads = s.linuxDownloads,
                status = s.releaseStatus, checking = s.releaseChecking, importLabel = "Importar ZIP de controlador…", canRestore = false,
                onSelect = a.onSelectLinux, onDelete = a.onRemoveLinux, onRefresh = a.onRefreshReleases,
                onDownload = a.onDownloadDriver, onImport = a.onImportLinux, onRestore = {}, onBack = { driverPage = null },
            )
            return
        }
        "panel" -> {
            DriverPage(
                title = "Controlador de pantalla",
                hint = "Lo usa el compositor. Reinicia la aplicación para aplicar el cambio.",
                rows = s.androidRows, selected = s.androidSelected, downloads = s.androidDownloads,
                status = s.releaseStatus, checking = s.releaseChecking, importLabel = "Importar ZIP de controlador Android…",
                canRestore = s.canRestoreBundled,
                onSelect = a.onSelectAndroid, onDelete = a.onRemoveAndroid, onRefresh = a.onRefreshReleases,
                onDownload = a.onDownloadDriver, onImport = a.onImportAndroid, onRestore = a.onRestoreBundled,
                onBack = { driverPage = null },
            )
            return
        }
    }
    SettingsPage(
        host,
        title = "Sesión de Steam",
        onBack = a.onDismiss,
        scroll = pageScroll,
    ) {
        SettingsGroup("Pantalla") {
            val default = SessionPrefs.defaultResolutionCap(s.mode)
            var editCustom by remember { mutableStateOf(false) }
            val custom = s.customResolution
            ChoiceRow(
                host, "res", "Resolución", "Se aplica en la próxima sesión.",
                listOf(
                    SessionPrefs.RESOLUTION_AUTO to "Automática según el perfil móvil",
                    540 to "Hasta 540p", 720 to "Hasta 720p", 900 to "Hasta 900p",
                    1080 to "Hasta 1080p", 0 to "La resolución del panel",
                ).map { (cap, label) -> cap to (if (cap == default) "$label - predeterminada" else label) } +
                    (CUSTOM to (custom?.let { "Personalizada · ${it.first}×${it.second}" } ?: "Personalizada…")),
                if (custom != null) CUSTOM else s.resolutionCap, note = "Automática mantiene 720p como base. 540p queda como opción manual para juegos especialmente pesados.",
                chipModifier = androidx.compose.ui.Modifier.focusRequester(firstChip),
                onPick = { v -> if (v == CUSTOM) editCustom = true else { a.onCustomResolution(null); a.onResolution(v) } },
            )
            ChoiceRow(
                host, "shape", "Proporción de pantalla",
                if (custom != null) "Definida por la resolución personalizada." else "Automático usa como mínimo 16:9.",
                com.droiddeck.launcher.session.SessionPrefs.shapeChoices, s.shapeMode, enabled = custom == null, onPick = a.onShape,
            )
            if (editCustom) CustomResolutionDialog(
                initial = custom,
                onSave = { size -> editCustom = false; a.onCustomResolution(size) },
                onDismiss = { editCustom = false },
            )
        }
        SettingsGroup("HDR") {
            ToggleRow(
                host, "hdr", "Salida HDR10",
                s.hdrReason?.let { "No disponible: $it." }
                    ?: "Reinicia la aplicación para aplicar el cambio.",
                checked = s.hdr && s.hdrReason == null, enabled = s.hdrReason == null, onChange = a.onHdr,
            )
        }
        SettingsGroup("Controladores") {
            SettingsRow("Controlador del entorno", "Lo usan Steam y los juegos. Se aplica en la próxima sesión.") {
                ValueChip(
                    s.linuxRows.firstOrNull { it.id == s.linuxSelected }?.name ?: "Predeterminado del entorno", open = false,
                    modifier = androidx.compose.ui.Modifier.focusRequester(runtimeChip),
                ) { openDriverPage("rt") }
            }
            if (DeviceSupport.hostVulkanBackend() == DeviceSupport.HostVulkanBackend.SYSTEM_VULKAN) {
                SettingsRow("Controlador de pantalla", "En Mali el compositor usa directamente el Vulkan del sistema Android.") {
                    Text(
                        "Vulkan del sistema",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SettingsRow("Controlador de pantalla", "Lo usa el compositor. Reinicia la aplicación para aplicar el cambio.") {
                    ValueChip(
                        s.androidRows.firstOrNull { it.id == s.androidSelected }?.name ?: "Automático - elegido según la GPU", open = false,
                        modifier = androidx.compose.ui.Modifier.focusRequester(displayChip),
                    ) { openDriverPage("panel") }
                }
            }
        }
        SettingsGroup("Táctil y controles") {
            ChoiceRow(
                host, "touch", "Táctil", null,
                listOf("auto" to "Automático", "touchpad" to "Panel táctil", "direct" to "Directo"), s.touchMode,
                note = "Automático usa entrada directa en Steam. Panel táctil queda disponible manualmente: arrastra para mover y toca para hacer clic.",
                onPick = a.onTouch,
            )
            if (steam && s.oscMode != null) ChoiceRow(
                host, "osc", "Controles en pantalla", null,
                listOf(
                    SessionPrefs.OSC_AUTO to "Automático",
                    SessionPrefs.OSC_ALWAYS to "Siempre",
                    SessionPrefs.OSC_STEAM_QAM to "Steam + acceso rápido",
                    SessionPrefs.OSC_NEVER to "Nunca",
                ), s.oscMode,
                note = "Automático muestra todos los controles cuando no hay mando. Steam + acceso rápido muestra solo esos botones.", onPick = a.onOsc,
            )
            if (steam) ChoiceRow(
                host, "back-actions", "Atrás", SessionPrefs.backActionsOrder(s.backActionsInverted),
                listOf(
                    false to SessionPrefs.BACK_MENU_THEN_QAM,
                    true to SessionPrefs.BACK_QAM_THEN_MENU,
                ), s.backActionsInverted, onPick = a.onBackActionsInverted,
            )
        }
        SettingsGroup("Sesión") {
            ChoiceRow(
                host, "suspend", "Comportamiento en segundo plano",
                "Define qué hace la sesión cuando la aplicación deja de estar visible o se apaga la pantalla.",
                listOf(
                    SessionPrefs.SUSPEND_AUTO to "Automático",
                    SessionPrefs.SUSPEND_MANUAL to "Manual",
                    SessionPrefs.SUSPEND_NEVER to "Nunca",
                ),
                s.suspendPolicy,
                note = "Automático pausa en segundo plano y reanuda al volver. Manual pausa y espera a que pulses Reanudar. Nunca mantiene la sesión activa.",
                onPick = a.onSuspendPolicy,
            )
        }
        if (steam) SettingsGroup("Inicio") {
            ToggleRow(
                host, "steam-startup", "Iniciar Steam al abrir DroidDeck",
                "Abre la sesión de Steam al iniciar DroidDeck.",
                s.runSteamAtStartup, onChange = a.onRunSteamAtStartup,
            )
        }
        if (steam) SettingsGroup("Decky") {
            val updateAvailable = s.deckyInstalled != null && s.deckyLatestRelease != null &&
                s.deckyInstalled != s.deckyLatestRelease.tag
            val status = when {
                s.deckyStage != null -> s.deckyStage
                s.deckyChecking -> "Buscando versiones compatibles…"
                s.deckyInstalled == null && s.deckyLatestRelease != null -> "Lista para instalar ${s.deckyLatestRelease.tag}."
                s.deckyInstalled == null -> "No se encontró una compilación compatible. Inténtalo más tarde."
                s.deckyLatestRelease == null -> "Instalado · ${s.deckyInstalled}"
                updateAvailable -> "Actualización disponible · ${s.deckyLatestRelease.tag}"
                else -> "Actualizado · ${s.deckyInstalled}"
            }
            SettingsRow("Cargador", status) {
                val action = when {
                    s.deckyStage != null -> "Procesando…"
                    s.deckyChecking -> "Comprobando…"
                    s.deckyInstalled == null && s.deckyLatestRelease != null -> "Instalar la última"
                    s.deckyInstalled != null && updateAvailable -> "Actualizar"
                    else -> "Comprobar"
                }
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    SecondaryButton(
                        action,
                        enabled = s.deckyStage == null && !s.deckyChecking && !s.deckySessionRunning,
                    ) {
                        if (s.deckyLatestRelease == null || (s.deckyInstalled != null && !updateAvailable)) a.onDeckyCheck()
                        else s.deckyLatestRelease?.let(a.onDeckyInstall)
                    }
                    if (s.deckyInstalled != null) SecondaryButton(
                        "Desinstalar",
                        enabled = s.deckyStage == null && !s.deckySessionRunning,
                    ) { confirmDeckyRemoval = true }
                }
            }
            if (s.deckyInstalled != null) SettingsRow(
                "Decky",
                if (s.deckyEnabled) "Se inicia con Steam. Mientras esté activado, otras aplicaciones del dispositivo podrían controlar Steam."
                else "Desactivado. Decky permanece apagado y el puerto de depuración remota de Steam queda cerrado.",
            ) {
                Switch(
                    checked = s.deckyEnabled,
                    onCheckedChange = a.onDeckyEnabled,
                    enabled = !s.deckySessionRunning && s.deckyStage == null,
                )
            }
            if (s.deckyStage != null && s.deckyPercent >= 0) {
                LinearProgressIndicator(
                    progress = { s.deckyPercent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (steam && s.steamChannel != null) SettingsGroup("Cliente") {
            ToggleRow(
                host, "steamdeck", "Modo Steam Deck",
                "Activa la interfaz Deck de Steam y los controles de rendimiento de acceso rápido. Se aplica en la próxima sesión.",
                s.steamDeckMode, onChange = a.onSteamDeckMode,
            )
            ChoiceRow(
                host, "channel", "Rama del cliente", "Versión del cliente de Steam que fuerza la sesión. Se aplica al próximo inicio; el cliente puede actualizarse una vez.",
                listOf("publicbeta" to "Beta pública", "steamdeck_publicbeta" to "Beta pública de Steam Deck"), s.steamChannel,
                note = "Beta pública era la rama usada por las sesiones anteriores. La beta pública de Steam Deck es la rama que necesita el modo Deck y la que usa el arranque inicial; el modo Deck la selecciona salvo que elijas otra aquí.",
                onPick = a.onSteamChannel,
            )
        }
        if (steam && s.addedGamesDirs != null) SettingsGroup("Juegos añadidos") {
            for (dir in s.addedGamesDirs) {
                val n = s.addedGames.count { it.folderPath.startsWith("$dir/") }
                ActionRow(
                    dir.substringAfterLast('/').ifEmpty { dir }, dir + " · " + (if (n == 0) "no se encontraron carpetas de juegos con un .exe" else "$n juego${if (n == 1) "" else "s"}") + ". " + stringResource(R.string.added_games_forget_hint),
                    "Olvidar", onClick = { a.onForgetAddedGamesDir(dir) },
                )
            }
            ActionRow(
                if (s.addedGamesDirs.isEmpty()) "Carpeta de juegos" else "Otra carpeta de juegos",
                stringResource(R.string.added_games_import_hint),
                "Añadir…", onClick = a.onPickAddedGamesDir,
            )
            ToggleRow(
                host, "addedArt", "Arte desde Steam",
                "Si un juego no tiene imágenes propias, se buscan en Steam la cápsula, cabecera, fondo y logotipo usando el nombre de la carpeta. Tus imágenes tienen prioridad: coloca cover.jpg, header.jpg, hero.jpg, logo.png o icon.png en la carpeta del juego o en su subcarpeta de arte.",
                s.addedGamesArt, onChange = a.onAddedGamesArt,
            )
            for (g in s.addedGames) ChoiceRow(
                host, "added:" + g.folderPath, g.folderName, "Inicia ${g.exeName}" + (if (s.addedGamesDirs.size > 1) " · en " + g.folderPath.substringBeforeLast('/').substringAfterLast('/') else ""),
                g.candidates + ("__pick__" to "Elegir otro archivo…"), g.exePath,
                note = "Archivos .exe encontrados en la carpeta del juego. Se elige primero el que coincide con el nombre de la carpeta y, si no existe, el de mayor tamaño; puedes cambiarlo aquí.",
                onPick = { path -> if (path == "__pick__") a.onPickAddedGameExe(g.folderPath) else a.onAddedGameExe(g.folderPath, path) },
            )
        }
        if (steam && s.fexPreset != null) SettingsGroup(stringResource(R.string.game_settings_title)) {
            ChoiceRow(
                host, "fex", stringResource(R.string.fex_preset_title), stringResource(R.string.fex_next_launch),
                FexPreset.all.map { it.id to stringResource(it.label) }, s.fexPreset,
                note = stringResource(FexPreset.byId(s.fexPreset).detail), onPick = a.onFexPreset,
            )
            GameEnvironmentRow()
            if (s.forceFullscreen != null) ToggleRow(
                host, "fill", "Estirar juegos para llenar la pantalla",
                "Mantiene a pantalla completa los juegos que cambian el tamaño de su propia ventana. Desactívalo si un juego aparece pequeño en una esquina. Se aplica en la próxima sesión.",
                s.forceFullscreen, onChange = a.onForceFullscreen,
            )
        }
        if (steam && s.directAudio != null && s.mic != null) SettingsGroup("Audio") {
            ToggleRow(host, "da", "DirectAudio para juegos", "Evita PulseAudio para reducir la latencia en juegos.", s.directAudio, onChange = a.onDirectAudio)
            ChoiceRow(
                host, "clientAudio", "Audio del cliente de Steam", "Clásico usa el receptor AAudio de 0.1.5. DirectAudio pasa por el relé. Se aplica en la próxima sesión.",
                listOf("classic" to "Clásico", "directaudio" to "DirectAudio"), if (s.clientDirectAudio) "directaudio" else "classic",
                onPick = { id -> a.onClientDirectAudio(id == "directaudio") },
            )
            ToggleRow(host, "mic", "Micrófono", "Usa el micrófono del dispositivo para el chat de voz.", s.mic, onChange = a.onMic)
        }
        if (steam && s.gameStorage != null) SettingsGroup("Almacenamiento de juegos") {
            val custom = s.gameStorage.isNotEmpty() && s.gameStorage != "off" && s.storageOptions.none { it.second == s.gameStorage }
            val options = buildList {
                add("" to ("Automático - usa la tarjeta SD cuando está disponible" + (if (s.storageOptions.isEmpty()) " (ninguna ahora)" else "")))
                add("off" to "Solo almacenamiento interno")
                for ((label, path) in s.storageOptions) add(path to label)
                if (custom) add(s.gameStorage to "Carpeta: ${s.gameStorage}")
            }
            val open = host.open == "storage"
            SettingsRow(
                "Biblioteca secundaria",
                stringResource(R.string.second_library_import_hint),
                highlighted = open,
            ) {
                androidx.compose.foundation.layout.Box {
                    ValueChip(options.firstOrNull { it.first == s.gameStorage }?.second?.substringBefore(" -") ?: "-", open) { host.open = if (open) null else "storage" }
                    AnchoredMenu(
                        open, onDismiss = { if (host.open == "storage") host.open = null }, title = "Biblioteca secundaria",
                        note = "Los juegos que leen datos continuamente desde SD o almacenamiento compartido pueden tener tirones. Es mejor mantenerlos en interno.",
                    ) { firstItemFocus ->
                        options.forEachIndexed { index, (path, label) ->
                            MenuItem(label, checked = path == s.gameStorage, focusRequester = if (index == 0) firstItemFocus else null) {
                                a.onGameStorage(path, if (path.isEmpty() || path == "off") "" else label.substringBefore(" ·"))
                                host.open = null
                            }
                        }
                        MenuItem("Elegir una carpeta…", checked = false) { host.open = null; a.onPickGameStorageFolder() }
                    }
                }
            }
        }
    }
    if (confirmDeckyRemoval) AlertDialog(
        onDismissRequest = { confirmDeckyRemoval = false },
        title = { Text("¿Desinstalar Decky Loader?") },
        text = { Text("Elimina el cargador y conserva tus plugins y ajustes.") },
        confirmButton = {
            TextButton(onClick = { confirmDeckyRemoval = false; a.onDeckyUninstall() }) { Text("Desinstalar") }
        },
        dismissButton = { TextButton(onClick = { confirmDeckyRemoval = false }) { Text("Cancelar") } },
    )
}

/** The Resolution menu's "Personalizada…" entry. */
private const val CUSTOM = -1

/** Width × height for the session, with the common handheld shapes one tap away. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CustomResolutionDialog(initial: Pair<Int, Int>?, onSave: (Pair<Int, Int>) -> Unit, onDismiss: () -> Unit) {
    var w by remember { mutableStateOf(initial?.first?.toString() ?: "") }
    var h by remember { mutableStateOf(initial?.second?.toString() ?: "") }
    val parsed = SessionPrefs.parseResolution("${w}x$h")
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resolución personalizada") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "Tamaño de pantalla de la sesión. Sustituye el límite y la proporción; si no coincide con la pantalla aparecerán barras.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    val numbers = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    androidx.compose.material3.OutlinedTextField(
                        w, { v -> w = v.filter(Char::isDigit).take(4) }, label = { Text("Ancho") },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f),
                    )
                    Text("×", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    androidx.compose.material3.OutlinedTextField(
                        h, { v -> h = v.filter(Char::isDigit).take(4) }, label = { Text("Alto") },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f),
                    )
                }
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    for ((pw, ph, tag) in listOf(Triple(960, 720, "4:3"), Triple(1024, 768, "4:3"), Triple(1280, 960, "4:3"), Triple(1280, 800, "16:10"), Triple(1152, 648, "16:9"), Triple(1280, 720, "16:9"))) {
                        androidx.compose.material3.AssistChip(
                            onClick = { w = pw.toString(); h = ph.toString() },
                            label = { Text("$pw×$ph · $tag", fontSize = 12.sp) },
                        )
                    }
                }
                if (parsed == null && (w.isNotEmpty() || h.isNotEmpty())) Text(
                    "Entre 320×240 y 3840×2160.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = parsed != null, onClick = { parsed?.let(onSave) }) { Text("Usar") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
