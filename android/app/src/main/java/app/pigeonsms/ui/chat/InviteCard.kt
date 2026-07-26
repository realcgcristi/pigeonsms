package app.pigeonsms.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.pigeonsms.data.SocialRepository
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.network.InvitePreviewResponse
import coil.compose.AsyncImage

/**
 * The popup behind a tapped `SPC-` invite code (2.9.5).
 *
 * An invite in a message used to be plain text you had to copy and paste into the
 * join field. Now it previews the nest — icon, name, member count — and joins in
 * place.
 *
 * The preview is read-only server-side: opening it never consumes one of the
 * invite's uses, so looking and declining costs the inviter nothing. An expired or
 * exhausted code returns `valid: false` rather than an error, so this shows a
 * plain "expired" line instead of something alarming.
 */
@Composable
fun InvitePreviewDialog(
    code: String,
    social: SocialRepository,
    onDismiss: () -> Unit,
    onJoin: (spaceId: String) -> Unit,
) {
    var preview by remember(code) { mutableStateOf<InvitePreviewResponse?>(null) }
    var joining by remember(code) { mutableStateOf(false) }
    var error by remember(code) { mutableStateOf<String?>(null) }

    LaunchedEffect(code) {
        runCatching { social.invitePreview(code) }
            .onSuccess { preview = it }
            .onFailure { error = it.message ?: "couldn't read that invite" }
    }

    val current = preview
    val space = current?.space
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current?.valid == true) "join this nest?" else "nest invite") },
        text = {
            when {
                error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                current == null -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
                !current.valid -> Text("this invite has expired or been used up.")
                else -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    space?.icon_key?.let { key ->
                        AsyncImage(
                            model = social.mediaUrl(key),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(CircleShape),
                        )
                    }
                    Column {
                        Text(space?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${space?.member_count ?: 0} members",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (current.already_member) {
                            Text(
                                "you're already in this nest",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (current?.valid == true && !current.already_member) {
                TextButton(
                    enabled = !joining,
                    onClick = {
                        joining = true
                        // Joining is the only call that consumes a use, so it happens
                        // here rather than when the preview opened.
                        onJoin(space?.id.orEmpty())
                    },
                ) { Text("join") }
            } else {
                TextButton(onClick = onDismiss) { Text("close") }
            }
        },
        dismissButton = {
            if (current?.valid == true && !current.already_member) {
                TextButton(onClick = onDismiss) { Text("not now") }
            }
        },
    )
}
