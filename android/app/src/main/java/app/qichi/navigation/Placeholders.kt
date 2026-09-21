package app.qichi.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar

/** 标签根页面的占位（后续任务替换为真正的页面）。 */
@Composable
fun TabPlaceholder(title: String) {
    Column(
        Modifier
            .fillMaxSize()
            .background(QichiTheme.colors.background)
            .statusBarsPadding(),
    ) {
        Text(
            text = title,
            style = QichiTheme.typography.pageTitle.copy(
                fontSize = QichiTheme.typography.pageTitle.fontSize * 1.25f,
                color = QichiTheme.colors.ink,
            ),
            modifier = Modifier
                .padding(start = Spacing.page, top = 28.dp)
                .semantics { heading() },
        )
    }
}

/** 二级页面的占位：只有返回条。 */
@Composable
fun PagePlaceholder(title: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(QichiTheme.colors.background),
    ) {
        BackBar(title = title, onBack = onBack)
    }
}
