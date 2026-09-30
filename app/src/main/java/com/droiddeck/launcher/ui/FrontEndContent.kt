package com.droiddeck.launcher.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.droiddeck.launcher.HomeApp
import java.io.File

// Panel principal de la edición Mali-first. El frontend público se limita a Steam,
// Biblioteca y Ajustes. El antiguo catálogo multipropósito queda deliberadamente
// fuera del flujo visible.

@Composable
internal fun Pane(
    s: FrontEndState,
    selected: String,
    a: FrontEndActions,
    page: (@Composable () -> Unit)?,
    modifier: Modifier,
    onSelect: (String) -> Unit,
    onAndroidAppClick: (HomeApp.LaunchableApp) -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onRequestWirelessAdb: (Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        CompositionLocalProvider(LocalNarrowPane provides (maxWidth < NarrowPaneWidth)) {
            val backdropArt: File? = when {
                s.pageKey != null && page != null -> null
                selected.startsWith("app:") -> s.steamGames.firstOrNull { "app:${it.appId}" == selected }?.art
                selected == "games" -> s.steamGames.maxByOrNull { it.lastPlayed }?.art
                else -> null
            }
            Backdrop(backdropArt)
            AnimatedContent(
                targetState = if (page != null && s.pageKey != null) {
                    s.pageKey
                } else if (selected.startsWith("app:")) {
                    "games"
                } else {
                    selected
                },
                transitionSpec = {
                    (fadeIn(Motion.tw(300, 80)) + slideInVertically(Motion.tw(420, 80)) { it / 24 })
                        .togetherWith(fadeOut(Motion.tw(170)) + slideOutVertically(Motion.tw(170)) { -it / 40 })
                        .apply { targetContentZIndex = 1f }
                },
                label = "pane",
            ) { key ->
                if (page != null && key == s.pageKey) {
                    page()
                } else {
                    Content(
                        s = s,
                        selected = if (key == "games") selected else key,
                        a = a,
                        modifier = Modifier.fillMaxSize(),
                        onSelect = onSelect,
                        onOpenDeveloperOptions = onOpenDeveloperOptions,
                        onRequestWirelessAdb = onRequestWirelessAdb,
                    )
                }
            }
        }
    }
}

@Composable
private fun Backdrop(art: File?) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Crossfade(targetState = art, animationSpec = Motion.tw(700), label = "backdrop") { a ->
        if (a != null && Build.VERSION.SDK_INT >= 31) {
            Box(modifier = Modifier.fillMaxSize().alpha(0.26f).blur(70.dp)) {
                AsyncImage(
                    model = a,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f },
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        listOf(pal.signal.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset.Zero,
                        radius = 1400f,
                    )
                )
            )
        }
    }
    Spacer(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color.Transparent, colors.background.copy(alpha = 0.35f)))
        )
    )
}

@Composable
private fun Content(
    s: FrontEndState,
    selected: String,
    a: FrontEndActions,
    modifier: Modifier,
    onSelect: (String) -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onRequestWirelessAdb: (Boolean) -> Unit,
) {
    val narrow = LocalNarrowPane.current
    val padH = if (narrow) 16.dp else 22.dp
    val padV = if (narrow) 12.dp else 18.dp

    when {
        selected == "setup" -> {
            Column(modifier = modifier.padding(horizontal = padH, vertical = padV)) {
                SetupPanel(s, a, onOpenDeveloperOptions, onRequestWirelessAdb)
            }
        }
        selected == "steam" -> SteamHome(s, a, modifier)
        selected == "games" || selected.startsWith("app:") -> GamesPage(s, a, selected, onSelect, modifier)
        else -> SteamHome(s, a, modifier)
    }
}
