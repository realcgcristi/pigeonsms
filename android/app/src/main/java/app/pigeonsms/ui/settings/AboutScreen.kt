package app.pigeonsms.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pigeonsms.BuildConfig
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.LocalUiSkin
import app.pigeonsms.design.theme.NovaColors
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.UiSkin
import app.pigeonsms.network.ReleaseDto
import kotlinx.coroutines.launch

// The public repo. cgcristi is the creator; the about screen links straight here.
private const val GITHUB_REPO = "https://github.com/cgcristi/pigeonsms"

/** The result of the last "check for updates" tap. */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseDto) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * about pigeonsms — version + build, the creator, a link to the source, an
 * in-app update check, and (admin only) a re-broadcast button that pings every
 * device that a release is out. Respects [LocalUiSkin] via [SettingsSubHeader] +
 * [GroupCard] + [Group] like the other settings screens.
 *
 * @param username the signed-in user's username; "admin" unlocks notify-everyone.
 */
@Composable
fun AboutScreen(username: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val skin = LocalUiSkin.current
    val nova = skin != UiSkin.Classic
    val api = (context.applicationContext as? app.pigeonsms.PigeonApp)?.container?.api
    val scope = rememberCoroutineScope()

    var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var notifyBusy by remember { mutableStateOf(false) }
    var notifyResult by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSubHeader("about", onBack)
        Column(Modifier.padding(horizontal = Spacing.l)) {
            // Identity block: app mark, name, version + build code.
            Column(
                Modifier.fillMaxWidth().padding(bottom = Spacing.m)
                    .clip(if (nova) NovaCorners.card else Corners.group)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ),
                    )
                    .padding(Spacing.l),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                }
                Text(
                    "pigeonsms",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = Spacing.m),
                )
                Text(
                    "v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
                Text(
                    "made by cgcristi",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }

            Group("source")
            GroupCard {
                AboutRow(Icons.Outlined.Code, "github", "view the source · cgcristi/pigeonsms") {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO))) }
                }
            }

            Group("updates")
            GroupCard {
                Column(Modifier.padding(Spacing.l)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
                            Text("check for updates", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            val sub = when (val u = update) {
                                UpdateState.Idle -> "you're on v${BuildConfig.VERSION_NAME}"
                                UpdateState.Checking -> "checking…"
                                UpdateState.UpToDate -> "you're up to date"
                                is UpdateState.Available -> "v${u.release.version_name} is available"
                                is UpdateState.Error -> u.message
                            }
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (update is UpdateState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (update is UpdateState.Checking) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            OutlinedButton(
                                onClick = {
                                    update = UpdateState.Checking
                                    scope.launch {
                                        update = try {
                                            val release = api?.latestRelease()
                                            when {
                                                release == null -> UpdateState.UpToDate
                                                release.version_code > BuildConfig.VERSION_CODE -> UpdateState.Available(release)
                                                else -> UpdateState.UpToDate
                                            }
                                        } catch (e: app.pigeonsms.network.PigeonApiException) {
                                            UpdateState.Error(e.message)
                                        } catch (e: Exception) {
                                            UpdateState.Error("couldn't check for updates")
                                        }
                                    }
                                },
                                shape = if (nova) NovaCorners.button else Corners.button,
                            ) { Text("check") }
                        }
                    }

                    // An update is out — surface the notes and a download+install CTA.
                    (update as? UpdateState.Available)?.let { avail ->
                        avail.release.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                            Text(
                                notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.m),
                            )
                        }
                        Button(
                            onClick = {
                                // Hands the release APK URL to the shared installer, which
                                // downloads it and fires the package-install intent.
                                app.pigeonsms.update.UpdateInstaller.downloadAndInstall(context, avail.release)
                            },
                            shape = if (nova) NovaCorners.button else Corners.button,
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
                        ) {
                            Icon(Icons.Outlined.SystemUpdate, null, modifier = Modifier.size(18.dp))
                            Text("download & install", modifier = Modifier.padding(start = Spacing.s))
                        }
                    }
                }
            }

            // Admin-only: re-broadcast the latest release to every device.
            if (username == "admin") {
                Group("admin")
                GroupCard {
                    Column(Modifier.padding(Spacing.l)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Campaign, null, tint = NovaColors.Cyan, modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
                                Text("notify everyone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    notifyResult ?: "re-broadcast this release to all devices",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (notifyBusy) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        notifyBusy = true
                                        notifyResult = null
                                        scope.launch {
                                            notifyResult = try {
                                                // Prefer the freshly-checked release; otherwise ask the server.
                                                val version = (update as? UpdateState.Available)?.release?.version_code
                                                    ?: api?.latestRelease()?.version_code
                                                    ?: BuildConfig.VERSION_CODE
                                                api?.notifyRelease(version)
                                                "sent — everyone was notified"
                                            } catch (e: app.pigeonsms.network.PigeonApiException) {
                                                if (e.code == "http_403") "not allowed" else e.message
                                            } catch (e: Exception) {
                                                "something went wrong"
                                            } finally {
                                                notifyBusy = false
                                            }
                                        }
                                    },
                                    shape = if (nova) NovaCorners.button else Corners.button,
                                ) { Text("notify") }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.huge))
        }
    }
}

/** A tappable info row inside a [GroupCard] — leading tinted icon, label + detail, no chevron noise. */
@Composable
private fun AboutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String?,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth()
            .clip(Corners.button)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(NovaCorners.iconBadge).background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
