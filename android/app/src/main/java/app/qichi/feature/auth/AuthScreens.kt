package app.qichi.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.FogSeaHero
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiKeyboard
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.TextAction

private enum class AuthPage { Welcome, Login, Register }

/** 未登录时的整个流程：欢迎 → 登录 / 注册。 */
@Composable
fun AuthFlow(viewModel: AuthViewModel = hiltViewModel()) {
    var page by rememberSaveable { mutableStateOf(AuthPage.Welcome) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = page != AuthPage.Welcome) { page = AuthPage.Welcome }
    when (page) {
        AuthPage.Welcome -> WelcomeScreen(onRegister = { page = AuthPage.Register }, onLogin = { page = AuthPage.Login })
        AuthPage.Login -> LoginScreen(state, viewModel, onBack = { page = AuthPage.Welcome }, onRegister = { page = AuthPage.Register })
        AuthPage.Register -> RegisterScreen(state, viewModel, onBack = { page = AuthPage.Welcome }, onLogin = { page = AuthPage.Login })
    }
}

@Composable
fun WelcomeScreen(onRegister: () -> Unit, onLogin: () -> Unit) {
    val colors = QichiTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            FogSeaHero(Modifier.fillMaxSize())
            // 插画底部淡入页面底色，不留硬边
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, colors.background))),
            )
        }
        Column(
            Modifier.padding(horizontal = Spacing.page, vertical = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                text = "栖迟",
                style = QichiTheme.typography.hubTitle.copy(color = colors.ink),
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.l))
            PrimaryButton("注册", onClick = onRegister, modifier = Modifier.fillMaxWidth())
            TextAction(
                text = "已有账号，登录",
                onClick = onLogin,
                color = colors.muted,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun FormPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(QichiTheme.colors.background)
            .imePadding(),
    ) {
        BackBar(title = title, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page, vertical = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            content()
        }
    }
}

@Composable
private fun GeneralError(message: String?) {
    if (message != null) {
        Text(message, style = QichiTheme.typography.caption.copy(color = QichiTheme.colors.accent))
    }
}

@Composable
private fun LoginScreen(state: AuthUiState, vm: AuthViewModel, onBack: () -> Unit, onRegister: () -> Unit) {
    FormPage(title = "登录", onBack = onBack) {
        QichiTextField(
            value = state.username, onValueChange = vm::onUsername, label = "用户名", error = state.error["username"],
            keyboardOptions = QichiKeyboard.username.copy(imeAction = ImeAction.Next),
        )
        QichiTextField(
            value = state.password, onValueChange = vm::onPassword, label = "密码", error = state.error["password"],
            password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.login() }),
        )
        GeneralError(state.error.message)
        Spacer(Modifier.height(Spacing.xs))
        PrimaryButton(if (state.submitting) "登录中" else "登录", onClick = vm::login, enabled = !state.submitting, modifier = Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextAction("还没有账号，注册", onClick = onRegister, color = QichiTheme.colors.muted)
        }
    }
}

@Composable
private fun RegisterScreen(state: AuthUiState, vm: AuthViewModel, onBack: () -> Unit, onLogin: () -> Unit) {
    FormPage(title = "注册", onBack = onBack) {
        QichiTextField(
            value = state.displayName, onValueChange = vm::onDisplayName, label = "怎么称呼你",
            placeholder = "阿栖", error = state.error["displayName"],
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        QichiTextField(
            value = state.username, onValueChange = vm::onUsername, label = "用户名",
            placeholder = "登录时用，小写字母或数字", error = state.error["username"],
            keyboardOptions = QichiKeyboard.username.copy(imeAction = ImeAction.Next),
        )
        QichiTextField(
            value = state.password, onValueChange = vm::onPassword, label = "密码",
            placeholder = "至少 8 位", error = state.error["password"], password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        )
        QichiTextField(
            value = state.inviteCode, onValueChange = vm::onInviteCode, label = "邀请码",
            placeholder = "第一个人不用填", error = state.error["inviteCode"],
            keyboardOptions = QichiKeyboard.code.copy(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.register() }),
        )
        GeneralError(state.error.message)
        Spacer(Modifier.height(Spacing.xs))
        PrimaryButton(if (state.submitting) "注册中" else "注册", onClick = vm::register, enabled = !state.submitting, modifier = Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextAction("已有账号，登录", onClick = onLogin, color = QichiTheme.colors.muted)
        }
        Spacer(Modifier.height(24.dp))
    }
}
