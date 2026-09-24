package app.qichi.feature.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.network.FileUrls
import app.qichi.core.ui.formatBytes
import app.qichi.shared.api.FileMeta
import app.qichi.shared.model.FileKind
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.io.File

/**
 * 图片气泡的大小：按原图比例，宽不超过 220、高不超过 260；特别细长的裁掉一部分（最短边 96）。
 * 不知道尺寸时用设计稿里的 180 × 132。
 */
internal fun imageBubbleSize(width: Int?, height: Int?): Pair<Dp, Dp> {
    if (width == null || height == null || width <= 0 || height <= 0) return 180.dp to 132.dp
    val aspect = width.toFloat() / height
    var w = 220f
    var h = w / aspect
    if (h > 260f) {
        h = 260f
        w = h * aspect
    }
    return w.coerceAtLeast(96f).dp to h.coerceAtLeast(96f).dp
}

@Composable
internal fun ImageBubble(
    file: FileMeta,
    shape: Shape,
    urls: FileUrls,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val (w, h) = imageBubbleSize(file.width, file.height)
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(urls.thumbnail(file.id)).crossfade(!QichiTheme.reduceMotion).build(),
        contentDescription = "图片",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(w, h)
            .clip(shape)
            .background(QichiTheme.colors.surface)
            .combinedClickable(onClickLabel = "查看大图", onClick = onOpen, onLongClickLabel = "更多操作", onLongClick = onLongPress),
    )
}

/** 文件气泡：图标、文件名、大小；下载中显示进度。 */
@Composable
internal fun FileBubble(
    file: FileMeta,
    shape: Shape,
    background: Modifier,
    maxWidth: Dp,
    downloading: Float?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        Modifier
            .widthIn(max = maxWidth)
            .clip(shape)
            .then(background)
            .combinedClickable(onClickLabel = "打开文件", onClick = onOpen, onLongClickLabel = "更多操作", onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(QichiIcons.File, contentDescription = null, tint = colors.ink, modifier = Modifier.size(24.dp))
        Column {
            Text(file.fileName, style = type.body.copy(fontSize = 14.tsp, lineHeight = 20.tsp, color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (downloading != null) "正在下载 ${(downloading * 100).toInt()}%" else formatBytes(file.sizeBytes),
                style = if (downloading != null) type.caption.copy(fontSize = 12.tsp, color = colors.muted) else type.numeral.copy(fontSize = 15.tsp, color = colors.muted),
            )
        }
    }
}

/** 正在上传的附件：本机预览 + 进度；失败时可重试或放弃。 */
@Composable
internal fun UploadItem(upload: Upload, maxWidth: Dp, onRetry: () -> Unit, onCancel: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val attachment = upload.attachment
    val shape = RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.End) {
        if (attachment.kind == FileKind.Image) {
            val (w, h) = imageBubbleSize(attachment.width, attachment.height)
            Box(Modifier.size(w, h).clip(shape)) {
                AsyncImage(
                    model = attachment.previewUri,
                    contentDescription = "正在发送的图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(colors.surface),
                )
                // 上传中蒙一层雾，进度用数字
                Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    if (upload.failed == null) {
                        Text("${(upload.progress * 100).toInt()}%", style = type.numeral.copy(fontSize = 22.tsp, color = colors.ink))
                    }
                }
            }
        } else {
            Row(
                Modifier.widthIn(max = maxWidth).clip(shape).background(colors.personA.copy(alpha = 0.13f)).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(QichiIcons.File, contentDescription = null, tint = colors.ink, modifier = Modifier.size(24.dp))
                Column {
                    Text(attachment.fileName, style = type.body.copy(fontSize = 14.tsp, lineHeight = 20.tsp, color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (upload.failed == null) "正在发送 ${(upload.progress * 100).toInt()}%" else formatBytes(attachment.sizeBytes),
                        style = type.caption.copy(fontSize = 12.tsp, color = colors.muted),
                    )
                }
            }
        }
        if (upload.failed != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(upload.failed, style = type.caption.copy(fontSize = 12.tsp, color = colors.accent))
                TextAction("重试", onClick = onRetry)
                TextAction("不发了", onClick = onCancel, color = colors.muted)
            }
        }
    }
}

/** 「+」：选图片或文件。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachSheet(onDismiss: () -> Unit, onImage: () -> Unit, onFile: () -> Unit) {
    val colors = QichiTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.paper) {
        Column(Modifier.padding(start = 28.dp, end = 28.dp, bottom = 28.dp)) {
            SheetRow("图片") { onImage(); onDismiss() }
            SheetRow("文件") { onFile(); onDismiss() }
        }
    }
}

@Composable
private fun SheetRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = QichiTheme.typography.bodyLarge.copy(color = QichiTheme.colors.ink),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 13.dp),
    )
}

/** 全屏看图（聊天里的图片）。 */
@Composable
internal fun ImageViewer(file: FileMeta, urls: FileUrls, onDismiss: () -> Unit) = app.qichi.core.ui.ImageViewer(file.id, urls, onDismiss)

/** 把下载好的附件交给别的应用打开。 */
internal fun openWithOtherApp(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "手机上没有能打开这种文件的应用", Toast.LENGTH_SHORT).show()
    }
}
