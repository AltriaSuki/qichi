package app.qichi.core.ui

import java.util.Locale
import kotlin.math.roundToLong

/** 文件大小的说法：「812 B」「218 KB」「3.4 MB」。 */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes / 1024.0).roundToLong().coerceAtLeast(1)} KB"
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}
