package app.pigeonsms.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.media.ExifInterface
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Spacing
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditedImage(
    val bytes: ByteArray,
    val filename: String,
    val type: String,
    val sentOriginal: Boolean,
)

private enum class EditorTool { Move, Draw, Blur }

private const val BLUR_PIXELATION = 16
private const val BLUR_STROKE_FRACTION = 0.12f

private enum class CropPreset(val label: String, val ratio: Float?) {
    Original("free", null),
    Square("1:1", 1f),
    Portrait("4:5", 4f / 5f),
    Landscape("16:9", 16f / 9f),
}

private data class EditStroke(val points: List<Offset>, val blur: Boolean = false)
private data class EditOverlay(val value: String, val x: Float, val y: Float, val emoji: Boolean)

@Composable
fun ImageEditorDialog(
    originalBytes: ByteArray,
    filename: String,
    type: String,
    initialSendOriginal: Boolean = false,
    onDismiss: () -> Unit,
    onSend: (EditedImage) -> Unit,
) {
    val decoded = remember(originalBytes) { decodeBitmap(originalBytes, 3_072) }
    if (decoded == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Can't edit this image") },
            text = { Text("The selected file isn't a supported bitmap image.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("close") } },
        )
        return
    }

    var rotation by remember { mutableStateOf(0) }
    var crop by remember { mutableStateOf(CropPreset.Original) }
    var tool by remember { mutableStateOf(EditorTool.Move) }
    var sendOriginal by remember { mutableStateOf(initialSendOriginal) }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val strokes = remember { mutableStateListOf<EditStroke>() }
    val overlays = remember { mutableStateListOf<EditOverlay>() }
    var overlayDialog by remember { mutableStateOf<Boolean?>(null) } // false=text, true=emoji
    var overlayValue by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var processingError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val preview = remember(decoded, rotation, crop) { transformBitmap(decoded, rotation, crop.ratio) }
    val pixelatedPreview = remember(preview) { pixelate(preview) }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(Icons.Rounded.Close, "Close image editor", tint = Color.White)
                    }
                    Text(filename, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            when {
                                currentStroke.isNotEmpty() -> currentStroke = emptyList()
                                strokes.isNotEmpty() -> strokes.removeAt(strokes.lastIndex)
                                overlays.isNotEmpty() -> overlays.removeAt(overlays.lastIndex)
                            }
                        },
                        enabled = !busy && (currentStroke.isNotEmpty() || strokes.isNotEmpty() || overlays.isNotEmpty()),
                    ) { Icon(Icons.Rounded.Undo, "Undo last edit", tint = Color.White) }
                    FilledIconButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.Default) {
                                        renderEditedImage(
                                            originalBytes = originalBytes,
                                            originalType = type,
                                            originalName = filename,
                                            rotation = rotation,
                                            cropRatio = crop.ratio,
                                            strokes = strokes.toList(),
                                            overlays = overlays.toList(),
                                            sendOriginal = sendOriginal,
                                        )
                                    }
                                }
                                busy = false
                                result.onSuccess(onSend).onFailure { processingError = "Couldn't process this image" }
                            }
                        },
                        enabled = !busy,
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Check, "Send edited image")
                    }
                }

                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    val imageRatio = preview.width.toFloat() / preview.height.coerceAtLeast(1)
                    val boxRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
                    val mediaModifier = if (imageRatio >= boxRatio) {
                        Modifier.fillMaxWidth().aspectRatio(imageRatio)
                    } else {
                        Modifier.fillMaxHeight().aspectRatio(imageRatio)
                    }
                    Box(mediaModifier.then(Modifier.background(Color.Black)), contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = "Image editing preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(tool, overlays.size, strokes.size) {
                                detectDragGestures(
                                    onDragStart = { point ->
                                        if (tool != EditorTool.Move) currentStroke = listOf(point.normalized(size.width, size.height))
                                    },
                                    onDragEnd = {
                                        if (tool != EditorTool.Move && currentStroke.size > 1) strokes += EditStroke(currentStroke, blur = tool == EditorTool.Blur)
                                        currentStroke = emptyList()
                                    },
                                    onDragCancel = { currentStroke = emptyList() },
                                ) { change, amount ->
                                    change.consume()
                                    if (tool != EditorTool.Move) {
                                        currentStroke = currentStroke + change.position.normalized(size.width, size.height)
                                    } else if (overlays.isNotEmpty()) {
                                        val last = overlays.last()
                                        overlays[overlays.lastIndex] = last.copy(
                                            x = (last.x + amount.x / size.width.coerceAtLeast(1)).coerceIn(0.05f, 0.95f),
                                            y = (last.y + amount.y / size.height.coerceAtLeast(1)).coerceIn(0.05f, 0.95f),
                                        )
                                    }
                                }
                            },
                        ) {
                            val liveStrokes = strokes.toList() +
                                listOfNotNull(currentStroke.takeIf { it.isNotEmpty() }?.let { EditStroke(it, blur = tool == EditorTool.Blur) })
                            val blurPaint = if (liveStrokes.any(EditStroke::blur)) {
                                blurStrokePaint(pixelatedPreview, size.width, size.height, size.minDimension * BLUR_STROKE_FRACTION)
                            } else null
                            liveStrokes.forEach { stroke ->
                                val points = stroke.points
                                if (points.size > 1) {
                                    if (stroke.blur && blurPaint != null) {
                                        val path = android.graphics.Path().apply {
                                            moveTo(points.first().x * size.width, points.first().y * size.height)
                                            points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                                        }
                                        drawContext.canvas.nativeCanvas.drawPath(path, blurPaint)
                                    } else {
                                        val path = Path().apply {
                                            moveTo(points.first().x * size.width, points.first().y * size.height)
                                            points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                                        }
                                        drawPath(path, Color(0xFFFFD54F), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
                                    }
                                }
                            }
                            drawContext.canvas.nativeCanvas.apply {
                                overlays.forEach { overlay ->
                                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        color = AndroidColor.WHITE
                                        textAlign = Paint.Align.CENTER
                                        textSize = if (overlay.emoji) size.minDimension * 0.16f else size.minDimension * 0.075f
                                        typeface = if (overlay.emoji) android.graphics.Typeface.DEFAULT else android.graphics.Typeface.DEFAULT_BOLD
                                        setShadowLayer(5f, 0f, 2f, AndroidColor.BLACK)
                                    }
                                    drawText(overlay.value, overlay.x * size.width, overlay.y * size.height, paint)
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.s),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EditorButton(Icons.Rounded.Crop, "Crop ${crop.label}") {
                        crop = CropPreset.entries[(crop.ordinal + 1) % CropPreset.entries.size]
                    }
                    EditorButton(Icons.Rounded.RotateRight, "Rotate image") { rotation = (rotation + 90) % 360 }
                    EditorButton(
                        if (tool == EditorTool.Draw) Icons.Rounded.OpenWith else Icons.Rounded.Draw,
                        if (tool == EditorTool.Draw) "Move overlay" else "Draw on image",
                        selected = tool == EditorTool.Draw,
                    ) { tool = if (tool == EditorTool.Draw) EditorTool.Move else EditorTool.Draw }
                    EditorButton(
                        Icons.Rounded.BlurOn,
                        if (tool == EditorTool.Blur) "Stop blurring" else "Blur parts of image",
                        selected = tool == EditorTool.Blur,
                    ) { tool = if (tool == EditorTool.Blur) EditorTool.Move else EditorTool.Blur }
                    EditorButton(Icons.Rounded.TextFields, "Add text") { overlayValue = ""; overlayDialog = false }
                    EditorButton(Icons.Rounded.EmojiEmotions, "Add emoji") { overlayValue = ""; overlayDialog = true }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.HighQuality, null, tint = Color.White.copy(alpha = 0.84f))
                    Text("Send original quality", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.s).weight(1f))
                    Switch(checked = sendOriginal, onCheckedChange = { sendOriginal = it }, enabled = !busy)
                }
            }
        }
    }

    overlayDialog?.let { emoji ->
        AlertDialog(
            onDismissRequest = { overlayDialog = null },
            title = { Text(if (emoji) "Add emoji" else "Add text") },
            text = {
                TextField(
                    value = overlayValue,
                    onValueChange = { overlayValue = it.take(if (emoji) 8 else 80) },
                    label = { Text(if (emoji) "Emoji" else "Text") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = overlayValue.trim()
                        if (value.isNotEmpty()) overlays += EditOverlay(value, 0.5f, 0.5f, emoji)
                        tool = EditorTool.Move
                        overlayDialog = null
                    },
                    enabled = overlayValue.isNotBlank(),
                ) { Text("add") }
            },
            dismissButton = { TextButton(onClick = { overlayDialog = null }) { Text("cancel") } },
        )
    }
    processingError?.let { message ->
        AlertDialog(
            onDismissRequest = { processingError = null },
            title = { Text("Image edit failed") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { processingError = null }) { Text("ok") } },
        )
    }
}

@Composable
private fun EditorButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val selectionColor by animateColorAsState(
        if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
        label = "editorToolSelection",
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier.background(selectionColor, Corners.chip),
    ) { Icon(icon, description, tint = Color.White) }
}

private fun Offset.normalized(width: Int, height: Int) = Offset(
    x = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f),
    y = (y / height.coerceAtLeast(1)).coerceIn(0f, 1f),
)

private fun decodeBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
    val decoded = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val largest = max(info.size.width, info.size.height)
                if (largest > maxDimension) {
                    val scale = maxDimension.toFloat() / largest
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { applyExifOrientation(bytes, it) }
        }
    }.getOrNull() ?: return null
    return decoded.copy(Bitmap.Config.ARGB_8888, false)
}

private fun applyExifOrientation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        @Suppress("DEPRECATION")
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return bitmap
    }
    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)
}

private fun transformBitmap(source: Bitmap, rotation: Int, cropRatio: Float?): Bitmap {
    val rotated = if (rotation == 0) source else Bitmap.createBitmap(
        source,
        0,
        0,
        source.width,
        source.height,
        Matrix().apply { postRotate(rotation.toFloat()) },
        true,
    )
    if (cropRatio == null) return rotated
    val current = rotated.width.toFloat() / rotated.height.coerceAtLeast(1)
    val (width, height) = if (current > cropRatio) {
        (rotated.height * cropRatio).toInt().coerceAtLeast(1) to rotated.height
    } else {
        rotated.width to (rotated.width / cropRatio).toInt().coerceAtLeast(1)
    }
    return Bitmap.createBitmap(rotated, (rotated.width - width) / 2, (rotated.height - height) / 2, width, height)
}

private fun applyMarkup(source: Bitmap, strokes: List<EditStroke>, overlays: List<EditOverlay>): Bitmap {
    if (strokes.isEmpty() && overlays.isEmpty()) return source
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = AndroidCanvas(output)
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(255, 213, 79)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = output.width.coerceAtMost(output.height) * 0.012f
    }
    val blurPaint = if (strokes.any(EditStroke::blur)) {
        blurStrokePaint(
            pixelated = pixelate(source),
            targetWidth = output.width.toFloat(),
            targetHeight = output.height.toFloat(),
            strokeWidth = output.width.coerceAtMost(output.height) * BLUR_STROKE_FRACTION,
        )
    } else null
    strokes.forEach { stroke ->
        if (stroke.points.size > 1) {
            val path = android.graphics.Path().apply {
                moveTo(stroke.points.first().x * output.width, stroke.points.first().y * output.height)
                stroke.points.drop(1).forEach { lineTo(it.x * output.width, it.y * output.height) }
            }
            canvas.drawPath(path, if (stroke.blur && blurPaint != null) blurPaint else strokePaint)
        }
    }
    overlays.forEach { overlay ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textAlign = Paint.Align.CENTER
            textSize = if (overlay.emoji) output.width.coerceAtMost(output.height) * 0.16f else output.width.coerceAtMost(output.height) * 0.075f
            typeface = if (overlay.emoji) android.graphics.Typeface.DEFAULT else android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(output.width.coerceAtMost(output.height) * 0.009f, 0f, 2f, AndroidColor.BLACK)
        }
        canvas.drawText(overlay.value, overlay.x * output.width, overlay.y * output.height, paint)
    }
    return output
}

private fun pixelate(source: Bitmap): Bitmap {
    val down = Bitmap.createScaledBitmap(
        source,
        (source.width / BLUR_PIXELATION).coerceAtLeast(1),
        (source.height / BLUR_PIXELATION).coerceAtLeast(1),
        true,
    )
    return Bitmap.createScaledBitmap(down, source.width, source.height, false)
}

private fun blurStrokePaint(pixelated: Bitmap, targetWidth: Float, targetHeight: Float, strokeWidth: Float): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.strokeWidth = strokeWidth
        shader = BitmapShader(pixelated, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setScale(targetWidth / pixelated.width.coerceAtLeast(1), targetHeight / pixelated.height.coerceAtLeast(1))
                },
            )
        }
    }

private fun scaleDown(source: Bitmap, maxDimension: Int): Bitmap {
    val largest = max(source.width, source.height)
    if (largest <= maxDimension) return source
    val scale = maxDimension.toFloat() / largest
    return Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
}

private fun renderEditedImage(
    originalBytes: ByteArray,
    originalType: String,
    originalName: String,
    rotation: Int,
    cropRatio: Float?,
    strokes: List<EditStroke>,
    overlays: List<EditOverlay>,
    sendOriginal: Boolean,
): EditedImage {
    val untouched = rotation == 0 && cropRatio == null && strokes.isEmpty() && overlays.isEmpty()
    if (sendOriginal && untouched) return EditedImage(originalBytes, originalName, originalType, true)

    val decoded = requireNotNull(decodeBitmap(originalBytes, if (sendOriginal) 8_192 else 2_560))
    val transformed = transformBitmap(decoded, rotation, cropRatio)
    val marked = applyMarkup(transformed, strokes, overlays)
    val outputBitmap = if (sendOriginal) marked else scaleDown(marked, 1_920)
    val png = originalType.equals("image/png", ignoreCase = true) && outputBitmap.hasAlpha()
    val output = ByteArrayOutputStream()
    outputBitmap.compress(
        if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
        if (sendOriginal) 98 else 82,
        output,
    )
    val extension = if (png) "png" else "jpg"
    val baseName = originalName.substringBeforeLast('.', originalName).ifBlank { "photo" }
    return EditedImage(output.toByteArray(), "$baseName.$extension", if (png) "image/png" else "image/jpeg", sendOriginal)
}

suspend fun squareImageVariant(bytes: ByteArray): ByteArray = withContext(Dispatchers.Default) {
    val decoded = requireNotNull(decodeBitmap(bytes, 2_048)) { "unsupported image" }
    val square = transformBitmap(decoded, 0, 1f)
    val output = ByteArrayOutputStream()
    scaleDown(square, 1_024).compress(Bitmap.CompressFormat.JPEG, 88, output)
    output.toByteArray()
}
