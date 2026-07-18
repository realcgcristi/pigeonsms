package app.pigeonsms.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.HowToVote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.ui.util.glassCard

enum class AttachmentAction { PhotosVideos, Camera, Documents, Poll, Audio, Event }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentOptionsSheet(
    isSpace: Boolean,
    onAction: (AttachmentAction) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("attach", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close attachment menu") }
            }
            val actions = buildList {
                add(AttachmentAction.PhotosVideos to (Icons.Rounded.PhotoLibrary to "Photos & videos"))
                add(AttachmentAction.Camera to (Icons.Outlined.PhotoCamera to "Camera"))
                add(AttachmentAction.Documents to (Icons.Rounded.Description to "Document"))
                add(AttachmentAction.Audio to (Icons.Rounded.AudioFile to "Audio"))
                if (isSpace) {
                    add(AttachmentAction.Poll to (Icons.Rounded.HowToVote to "Poll"))
                    add(AttachmentAction.Event to (Icons.Rounded.CalendarMonth to "Event"))
                }
            }
            actions.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    row.forEach { (action, iconAndLabel) ->
                        val (icon, label) = iconAndLabel
                        Surface(
                            onClick = { onAction(action) },
                            shape = Corners.card,
                            color = Color.Transparent,
                            modifier = Modifier.weight(1f).glassCard(Corners.card),
                        ) {
                            Column(Modifier.padding(vertical = Spacing.m), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(icon, label, modifier = Modifier.size(24.dp))
                                Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = Spacing.xs))
                            }
                        }
                    }
                    repeat(3 - row.size) { SpacerCell() }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s)) { Text("cancel") }
        }
    }
}

@Composable
private fun RowScope.SpacerCell() { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
