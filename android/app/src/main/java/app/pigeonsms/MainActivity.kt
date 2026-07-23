package app.pigeonsms

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pigeonsms.data.LocalSession
import app.pigeonsms.design.theme.rememberWallpaperBrush
import app.pigeonsms.design.theme.wallpaperByKey
import coil.compose.AsyncImage
import app.pigeonsms.data.ThemeMode
import app.pigeonsms.data.ThemePrefs
import app.pigeonsms.design.theme.PigeonTheme
import app.pigeonsms.design.theme.PigeonThemeMode
import app.pigeonsms.ui.AppShell
import app.pigeonsms.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.flow.map

private sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState
    data class LoggedIn(val session: LocalSession) : AuthState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        publishNotificationIntent(intent)
        enableEdgeToEdge()
        requestHighRefreshRate()
        window.setBackgroundDrawable(null) // one fewer opaque layer under the window → less overdraw
        val container = (application as PigeonApp).container
        val startupCrash = CrashReporter.consume(this)
        setContent {
            val theme by container.themeStore.prefs.collectAsState(initial = ThemePrefs())
            val glassTint = remember(theme.wallpaper) {
                val wp = theme.wallpaper
                if (wp == null || wp.startsWith("custom:")) {
                    androidx.compose.ui.graphics.Color.White
                } else {
                    app.pigeonsms.design.theme.PigeonWallpapers.firstOrNull { it.key == wp }
                        ?.stops?.takeIf { it.isNotEmpty() }
                        ?.let { stops ->
                            val r = stops.map { it.red }.average().toFloat()
                            val g = stops.map { it.green }.average().toFloat()
                            val b = stops.map { it.blue }.average().toFloat()
                            // blend toward white so glass stays bright but wallpaper-tinted
                            androidx.compose.ui.graphics.Color(r * 0.45f + 0.55f, g * 0.45f + 0.55f, b * 0.45f + 0.55f)
                        } ?: androidx.compose.ui.graphics.Color.White
                }
            }
            PigeonTheme(
                mode = when (theme.mode) {
                    ThemeMode.System -> PigeonThemeMode.System
                    ThemeMode.Dark -> PigeonThemeMode.Dark
                    ThemeMode.Oled -> PigeonThemeMode.Oled
                    ThemeMode.Light -> PigeonThemeMode.Light
                },
                accentKey = theme.accent,
                reducedMotion = theme.reducedMotion,
                liquidGlass = theme.liquidGlass,
                glassTint = glassTint,
                dynamicColor = theme.dynamicColor,
                skin = when (theme.uiSkin) {
                    "nova" -> app.pigeonsms.design.theme.UiSkin.Nova
                    "galaxy" -> app.pigeonsms.design.theme.UiSkin.Galaxy
                    else -> app.pigeonsms.design.theme.UiSkin.Classic
                },
            ) {
                var crash by remember { mutableStateOf(startupCrash) }
                val pending = crash
                if (pending != null) {
                    CrashScreen(pending) { crash = null }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        AppWallpaper(theme.wallpaper, theme.wallpaperDim)
                        val auth by container.sessionStore.session
                            .map<LocalSession?, AuthState> { if (it == null) AuthState.LoggedOut else AuthState.LoggedIn(it) }
                            .collectAsState(initial = AuthState.Loading)

                        when (val a = auth) {
                            AuthState.Loading -> Splash()
                            // key on the auth state so the onboarding graph is fresh each logout —

                            AuthState.LoggedOut -> OnboardingScreen()
                            is AuthState.LoggedIn -> AppShell(session = a.session)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        publishNotificationIntent(intent)
    }

    private fun publishNotificationIntent(intent: Intent?) {
        (application as? PigeonApp)?.publishNotificationIntent(intent)
    }

    private fun requestHighRefreshRate() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        val best = display?.supportedModes?.maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId

            preferredRefreshRate = best.refreshRate
        }
    }

    override fun onResume() {
        super.onResume()
        requestHighRefreshRate()
    }
}

@Composable
private fun CrashScreen(trace: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(20.dp),
    ) {
        Text(
            "PigeonSMS hit a snag",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "It recovered. Copy this and send it over so it can be fixed for good.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        SelectionContainer(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                trace,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(trace)) },
                modifier = Modifier.weight(1f),
            ) { Text("Copy") }
            Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Continue") }
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
}

@Composable
private fun AppWallpaper(key: String?, dim: Float) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            key != null && key.startsWith("custom:") -> {
                AsyncImage(
                    model = android.net.Uri.parse(key.removePrefix("custom:")),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim.coerceIn(0f, 0.85f))))
            }
            else -> {
                val preset = wallpaperByKey(key)
                if (preset != null) {
                    Box(Modifier.fillMaxSize().background(rememberWallpaperBrush(preset.stops)))
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim.coerceIn(0f, 0.85f))))
                }
            }
        }
    }
}
