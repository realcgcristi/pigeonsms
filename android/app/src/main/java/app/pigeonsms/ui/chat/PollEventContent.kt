package app.pigeonsms.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HowToVote
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.network.EventMetadataDto
import app.pigeonsms.network.PollDto
import app.pigeonsms.ui.util.LiquidSwitch
import app.pigeonsms.ui.util.glassCard
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val pollEventJson = Json { ignoreUnknownKeys = true }

private const val POLL_MAX_OPTIONS = 10
private const val HOUR_MS = 60L * 60 * 1000

// --- creation dialogs -------------------------------------------------------

@Composable
fun CreatePollDialog(
    onDismiss: () -> Unit,
    onCreate: (question: String, options: List<String>, anonymous: Boolean) -> Unit,
) {
    var question by rememberSaveable { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var anonymous by rememberSaveable { mutableStateOf(false) }

    val filled = options.map { it.trim() }.filter { it.isNotEmpty() }
    val hasDuplicates = filled.map { it.lowercase() }.toSet().size != filled.size
    val valid = question.isNotBlank() && filled.size >= 2 && !hasDuplicates

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Corners.card,
        icon = { Icon(Icons.Rounded.HowToVote, null) },
        title = { Text("new poll") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).animateContentSize(tween(160)),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(300) },
                    label = { Text("question") },
                    shape = Corners.input,
                    modifier = Modifier.fillMaxWidth(),
                )
                options.forEachIndexed { index, value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { options[index] = it.take(120) },
                            placeholder = { Text("option ${index + 1}") },
                            shape = Corners.input,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (options.size > 2) {
                            IconButton(onClick = { options.removeAt(index) }) {
                                Icon(Icons.Rounded.Close, "remove option ${index + 1}")
                            }
                        }
                    }
                }
                if (options.size < POLL_MAX_OPTIONS) {
                    TextButton(onClick = { options.add("") }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("add option")
                    }
                }
                if (hasDuplicates) {
                    Text(
                        "options must be unique",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                // the backend is single-choice only, so there is no multi-select toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("anonymous", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "hide who voted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LiquidSwitch(checked = anonymous, onCheckedChange = { anonymous = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onCreate(question.trim(), filled, anonymous) },
            ) { Text("create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel") } },
    )
}

@Composable
fun CreateEventDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, startsAt: Long, endsAt: Long?, location: String?, description: String?) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var startsAt by rememberSaveable { mutableStateOf(nextFullHour()) }
    var endsAt by rememberSaveable { mutableStateOf<Long?>(null) }

    val endInvalid = endsAt?.let { it < startsAt } == true
    val valid = title.isNotBlank() && !endInvalid

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Corners.card,
        icon = { Icon(Icons.Rounded.CalendarMonth, null) },
        title = { Text("new event") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).animateContentSize(tween(160)),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    label = { Text("title") },
                    shape = Corners.input,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DateTimeRow(label = "starts", value = startsAt) { picked ->
                    val previous = startsAt
                    startsAt = picked
                    // keep the end anchored relative to the start when it would go invalid
                    endsAt = endsAt?.let { if (it < picked) picked + (it - previous).coerceAtLeast(HOUR_MS) else it }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("end time", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    LiquidSwitch(
                        checked = endsAt != null,
                        onCheckedChange = { endsAt = if (it) startsAt + HOUR_MS else null },
                    )
                }
                endsAt?.let { end ->
                    DateTimeRow(label = "ends", value = end) { endsAt = it }
                    if (endInvalid) {
                        Text(
                            "the end must be after the start",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it.take(200) },
                    label = { Text("location (optional)") },
                    shape = Corners.input,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(1000) },
                    label = { Text("description (optional)") },
                    shape = Corners.input,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onCreate(
                        title.trim(),
                        startsAt,
                        endsAt,
                        location.trim().ifEmpty { null },
                        description.trim().ifEmpty { null },
                    )
                },
            ) { Text("create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(label: String, value: Long, onChange: (Long) -> Unit) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val calendar = remember(value) { Calendar.getInstance().apply { timeInMillis = value } }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(48.dp))
        Surface(
            onClick = { showDate = true },
            shape = Corners.chip,
            color = Color.Transparent,
            modifier = Modifier.glassCard(Corners.chip),
        ) {
            Text(
                formatDate(value),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
            )
        }
        Surface(
            onClick = { showTime = true },
            shape = Corners.chip,
            color = Color.Transparent,
            modifier = Modifier.glassCard(Corners.chip),
        ) {
            Text(
                formatTime(value),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
            )
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = localDateAsUtcMillis(value))
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        onChange(
                            combineDateAndTime(
                                picked,
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                            ),
                        )
                    }
                    showDate = false
                }) { Text("ok") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("cancel") } },
        ) { DatePicker(state) }
    }

    if (showTime) {
        val state = rememberTimePickerState(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE),
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            shape = Corners.card,
            title = { Text("pick a time") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state) } },
            confirmButton = {
                TextButton(onClick = {
                    onChange(withTimeOfDay(value, state.hour, state.minute))
                    showTime = false
                }) { Text("ok") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("cancel") } },
        )
    }
}

// --- in-chat renderers ------------------------------------------------------

@Composable
fun PollMessageContent(
    pollJson: String?,
    enabled: Boolean,
    onVote: (String?) -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
) {
    val poll = remember(pollJson) {
        pollJson?.let { runCatching { pollEventJson.decodeFromString<PollDto>(it) }.getOrNull() }
    }
    if (poll == null) {
        Text("poll unavailable", color = contentColor.copy(alpha = 0.55f), style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        return
    }

    Column(modifier.widthIn(min = 220.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(Icons.Rounded.HowToVote, null, Modifier.size(16.dp), tint = contentColor.copy(alpha = 0.85f))
            Text(
                poll.question,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }
        Spacer(Modifier.height(Spacing.xxs))
        poll.options.forEach { option ->
            val fraction = if (poll.total_votes > 0) option.votes.toFloat() / poll.total_votes else 0f
            val fill by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(280), label = "pollFill")
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(Corners.chip)
                    .background(contentColor.copy(alpha = 0.10f))
                    .then(
                        if (option.me) Modifier.border(1.5.dp, contentColor.copy(alpha = 0.85f), Corners.chip)
                        else Modifier
                    )
                    .clickable(enabled = enabled) { onVote(if (option.me) null else option.id) },
            ) {
                Box(Modifier.matchParentSize()) {
                    if (fill > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(fill)
                                .fillMaxHeight()
                                .background(contentColor.copy(alpha = if (option.me) 0.26f else 0.14f)),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (option.me) {
                        Icon(Icons.Rounded.CheckCircle, "your vote", Modifier.size(16.dp), tint = contentColor)
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    Text(
                        option.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (poll.total_votes > 0) {
                        Spacer(Modifier.width(Spacing.s))
                        Text(
                            "${(fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
        Text(
            buildString {
                append(if (poll.total_votes == 1) "1 vote" else "${poll.total_votes} votes")
                if (poll.anonymous) append(" · anonymous")
            },
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.65f),
        )
    }
}

@Composable
fun EventMessageContent(
    metadataJson: String?,
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
) {
    val event = remember(metadataJson) {
        metadataJson?.let { runCatching { pollEventJson.decodeFromString<EventMetadataDto>(it) }.getOrNull() }
    }
    if (event == null) {
        Text("event unavailable", color = contentColor.copy(alpha = 0.55f), style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        return
    }

    Row(modifier.widthIn(min = 220.dp), verticalAlignment = Alignment.Top) {
        Column(
            Modifier
                .clip(Corners.chip)
                .background(contentColor.copy(alpha = 0.12f))
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                monthLabel(event.starts_at).uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor.copy(alpha = 0.8f),
            )
            Text(
                dayLabel(event.starts_at),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
        Spacer(Modifier.width(Spacing.m))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                event.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            Text(eventTimeLine(event.starts_at, event.ends_at), style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.8f))
            event.location?.takeIf { it.isNotBlank() }?.let { where ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Icon(Icons.Rounded.Place, null, Modifier.size(16.dp), tint = contentColor.copy(alpha = 0.7f))
                    Text(where, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            event.description?.takeIf { it.isNotBlank() }?.let { about ->
                Text(
                    about,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// --- time helpers -----------------------------------------------------------

private fun nextFullHour(): Long = Calendar.getInstance().apply {
    add(Calendar.HOUR_OF_DAY, 1)
    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun localDateAsUtcMillis(millis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = millis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun combineDateAndTime(dateUtcMillis: Long, hour: Int, minute: Int): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateUtcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), hour, minute)
    }.timeInMillis
}

private fun withTimeOfDay(millis: Long, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun formatDate(millis: Long): String =
    SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(millis))

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

private fun monthLabel(millis: Long): String =
    SimpleDateFormat("MMM", Locale.getDefault()).format(Date(millis))

private fun dayLabel(millis: Long): String =
    SimpleDateFormat("d", Locale.getDefault()).format(Date(millis))

private fun eventTimeLine(startsAt: Long, endsAt: Long?): String {
    val start = "${formatDate(startsAt)} · ${formatTime(startsAt)}"
    if (endsAt == null) return start
    val sameDay = formatDate(startsAt) == formatDate(endsAt)
    return if (sameDay) "$start – ${formatTime(endsAt)}"
    else "$start – ${formatDate(endsAt)} · ${formatTime(endsAt)}"
}
