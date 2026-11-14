package ai.bench.app.ui

import ai.bench.app.ui.theme.Bench
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "12 KB", "1.4 MB": a file's size for a chip. */
fun sizeLabel(bytes: Long): String? = when {
    bytes < 0 -> null
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
}

/**
 * What a message will carry, before it is sent: a photo as its thumbnail, any other file as
 * a chip with its name and size; each with an × to take it out. Attachments show what enters
 * the prompt rather than a bare name (the AI composer teardowns).
 */
@Composable
fun AttachmentTile(name: String, uri: Uri?, image: Boolean, size: Long, progress: Float?, error: String?, onRemove: () -> Unit) {
    val c = Bench.colors
    if (image && uri != null && error == null) {
        val context = LocalContext.current
        val thumb by produceState<ImageBitmap?>(null, uri) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(uri, android.util.Size(168, 168), null).asImageBitmap() }.getOrNull()
            }
        }
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(c.surface3)) {
            thumb?.let { Image(it, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (progress != null && progress < 1f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter))
            Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Close, "Remove $name", tint = Color.White, modifier = Modifier.size(13.dp)) }
        }
        return
    }
    Row(
        Modifier.height(56.dp).widthIn(max = 220.dp).clip(RoundedCornerShape(12.dp)).background(c.surface3).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Description, null, tint = if (error != null) c.bad else c.muted, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (error != null) c.bad else c.text)
            Text(error ?: sizeLabel(size) ?: "", fontSize = 11.5.sp, color = if (error != null) c.bad else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null && progress < 1f && error == null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.padding(top = 3.dp).widthIn(max = 120.dp).height(2.dp))
        }
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, "Remove $name", tint = c.muted, modifier = Modifier.size(16.dp))
        }
    }
}
