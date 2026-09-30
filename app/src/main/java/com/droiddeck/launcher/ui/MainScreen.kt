package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.HomeApp
import com.droiddeck.launcher.input.SecondScreenDisplay

@Composable
fun ChooseAppDisplayDialog(
    app: HomeApp.LaunchableApp,
    secondaryDisplay: SecondScreenDisplay?,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Abrir ${app.label}") },
        text = {
            Text(
                secondaryDisplay?.let { "Elige una pantalla. Secundaria: ${it.label}." }
                    ?: "La pantalla secundaria ya no está disponible.",
            )
        },
        confirmButton = { TextButton(onClick = onPrimary) { Text("Pantalla principal") } },
        dismissButton = {
            TextButton(onClick = onSecondary, enabled = secondaryDisplay != null) {
                Text("Pantalla secundaria")
            }
        },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun CreditsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Créditos") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("The412Banner: aplicación, compositor, entorno y sesión de Steam.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text("maxjivi05: entorno gamescope y soporte de controles, basado en WinNative.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text(
                    "GPL-3.0. Steam, Steam Deck y Proton son marcas de Valve. Sin afiliación con Valve. El software de terceros conserva las licencias de sus autores.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Aceptar") } },
    )
}
