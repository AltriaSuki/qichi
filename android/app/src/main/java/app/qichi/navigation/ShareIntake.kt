package app.qichi.navigation

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.data.IdeaRepository
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.share.ShareInbox
import app.qichi.core.share.Shared
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 从别的 App 分享进来（P16-03）：问一句放到哪里。 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    private val inbox: ShareInbox,
    private val ideas: IdeaRepository,
) : ViewModel() {
    val pending: StateFlow<Shared<Uri>?> = inbox.pending

    fun dismiss() = inbox.dismiss()

    fun toChat(roomId: UUID) = inbox.sendToChat(roomId)

    /** 存成灵感：和在灵感页记一条一样（先存本机、经发件箱发出）。 */
    fun toIdea(roomId: UUID, onSaved: () -> Unit) {
        val text = pending.value?.text ?: return
        inbox.dismiss()
        viewModelScope.launch { if (ideas.add(roomId, text) != null) onSaved() }
    }
}

/**
 * 有分享进来的内容时弹出：「发到聊天」（文字放进输入框看一眼再发，照片开始上传）或「存成灵感」（只有文字时）。
 * 只有照片时不用问，直接去聊天。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareIntake(roomId: UUID, onOpenChat: () -> Unit) {
    val viewModel: ShareViewModel = hiltViewModel()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val content = pending ?: return
    val context = LocalContext.current
    val toChat = {
        viewModel.toChat(roomId)
        onOpenChat()
    }
    if (content.text == null) {
        LaunchedEffect(content) { toChat() }
        return
    }
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    ModalBottomSheet(onDismissRequest = viewModel::dismiss, containerColor = colors.paper) {
        Column(Modifier.padding(start = Spacing.page, end = Spacing.page, bottom = Spacing.page)) {
            Text(
                if (content.images.isEmpty()) content.text else "${content.images.size} 张照片 · ${content.text}",
                style = type.body.copy(color = colors.muted),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = Spacing.s),
            )
            SheetRow("发到聊天", toChat)
            // 照片存不进灵感：有照片时只能发到聊天
            if (content.images.isEmpty()) {
                SheetRow("存成灵感") {
                    viewModel.toIdea(roomId) { Toast.makeText(context, "存进灵感了", Toast.LENGTH_SHORT).show() }
                }
            }
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
            .heightIn(min = Sizes.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.s),
    )
}
