package app.pigeonsms.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Dimens
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.ui.pigeonVm

@Composable
fun OnboardingScreen() {
    val vm: OnboardingViewModel = pigeonVm { c, _ -> OnboardingViewModel(c.authRepository) }
    val state by vm.state.collectAsState()

    BackHandler(enabled = state.step != OnboardingStep.Welcome) { vm.goTo(OnboardingStep.Welcome) }

    val accent = MaterialTheme.colorScheme.primary
    val lavender = MaterialTheme.colorScheme.secondary
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // soft ambient wash: accent glow top-leading, lavender bottom-trailing
            .drawBehind {
                val r1 = size.maxDimension * 0.65f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.08f),
                        radius = r1,
                    ),
                    radius = r1,
                    center = Offset(size.width * 0.12f, size.height * 0.08f),
                )
                val r2 = size.maxDimension * 0.55f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(lavender.copy(alpha = 0.08f), Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.92f),
                        radius = r2,
                    ),
                    radius = r2,
                    center = Offset(size.width * 0.92f, size.height * 0.92f),
                )
            }
            .systemBarsPadding()
            .imePadding(),
    ) {
        if (state.step != OnboardingStep.Welcome) {
            IconButton(onClick = { vm.goTo(OnboardingStep.Welcome) }, modifier = Modifier.padding(Spacing.s)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                val fwd = targetState.ordinal >= initialState.ordinal
                val dir = if (fwd) 1 else -1
                // springy step change: the incoming step slides in and settles
                val slideSpring = spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = IntOffset.VisibilityThreshold,
                )
                (slideInHorizontally(slideSpring) { it / 3 * dir } + fadeIn()) togetherWith
                    (slideOutHorizontally(slideSpring) { -it / 3 * dir } + fadeOut())
            },
            label = "step",
        ) { step ->
            when (step) {
                OnboardingStep.Welcome -> Welcome(vm)
                OnboardingStep.Invite -> Invite(vm, state)
                OnboardingStep.Details -> Details(vm, state)
                OnboardingStep.Login -> Login(vm, state)
            }
        }
    }
}

@Composable
private fun Step(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.xl), verticalArrangement = Arrangement.Center, content = content)
}

@Composable
private fun Welcome(vm: OnboardingViewModel) = Step {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(112.dp)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ),
                CircleShape,
            )
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)), CircleShape)
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.Transparent, accent.copy(alpha = 0.25f))),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // foreground asset keeps adaptive-icon padding, so scale the glyph back up
        Image(
            painterResource(app.pigeonsms.R.mipmap.ic_fg_icon),
            contentDescription = null,
            modifier = Modifier.size(112.dp).graphicsLayer { scaleX = 1.55f; scaleY = 1.55f },
        )
    }
    Spacer(Modifier.height(Spacing.xl))
    Text("pigeonsms", style = MaterialTheme.typography.displaySmall, letterSpacing = (-0.5).sp, color = MaterialTheme.colorScheme.onSurface)
    Text("your flock's cozy corner", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.s))
    Spacer(Modifier.height(Spacing.huge))
    Primary("i have an invite", false) { vm.goTo(OnboardingStep.Invite) }
    TextButton(onClick = { vm.goTo(OnboardingStep.Login) }, modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)) {
        Text("i already have an account", color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun Invite(vm: OnboardingViewModel, s: OnboardingUiState) = Step {
    Head("your invite", "pigeonsms is invite-only. ask a friend for a code.")
    Field(s.invite, vm::setInvite, "PGN-XXXX-XXXX")
    Err(s.error); Spacer(Modifier.height(Spacing.l)); Primary("continue", s.loading, vm::submitInvite)
}

@Composable
private fun Details(vm: OnboardingViewModel, s: OnboardingUiState) = Step {
    Head("make it yours", "pick a name your friends will recognize.")
    Field(s.username, vm::setUsername, "username"); Spacer(Modifier.height(Spacing.m))
    Field(s.email, vm::setEmail, "email", KeyboardType.Email); Spacer(Modifier.height(Spacing.m))
    Field(s.password, vm::setPassword, "password", isPassword = true)
    Err(s.error); Spacer(Modifier.height(Spacing.l)); Primary("create account", s.loading, vm::submitSignup)
}

@Composable
private fun Login(vm: OnboardingViewModel, s: OnboardingUiState) = Step {
    Head("welcome back", "")
    Field(s.loginField, vm::setLoginField, "username or email"); Spacer(Modifier.height(Spacing.m))
    Field(s.loginPassword, vm::setLoginPassword, "password", isPassword = true)
    if (s.needsTotp) { Spacer(Modifier.height(Spacing.m)); Field(s.totp, vm::setTotp, "2fa code", KeyboardType.Number) }
    Err(s.error); Spacer(Modifier.height(Spacing.l)); Primary("sign in", s.loading, vm::submitLogin)
}

@Composable
private fun ColumnScope.Head(title: String, sub: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge, letterSpacing = (-0.5).sp, color = MaterialTheme.colorScheme.onSurface)
    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.s))
    Spacer(Modifier.height(Spacing.xl))
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, keyboardType: KeyboardType = KeyboardType.Text, isPassword: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true, shape = Corners.input,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Primary(text: String, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = !loading, shape = Corners.button,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
        modifier = Modifier.fillMaxWidth().height(Dimens.ctaHeight),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Err(error: String?) {
    if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.s))
}
