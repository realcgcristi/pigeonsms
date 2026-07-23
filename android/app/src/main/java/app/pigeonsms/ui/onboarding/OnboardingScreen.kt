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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.pigeonsms.design.components.NovaPillButton
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Dimens
import app.pigeonsms.design.theme.LocalExperimentalRedesign
import app.pigeonsms.design.theme.LocalReducedMotion
import app.pigeonsms.design.theme.LocalUiSkin
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.NovaDepth
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.UiSkin
import app.pigeonsms.design.theme.heroAppear
import app.pigeonsms.design.theme.novaAuroraBackground
import app.pigeonsms.design.theme.novaHalo
import app.pigeonsms.design.theme.rememberNovaPulse
import app.pigeonsms.ui.pigeonVm

@Composable
fun OnboardingScreen() {

    when (LocalUiSkin.current) {
        UiSkin.Nova -> ExOnboardingScreen()
        else -> GalaxyOnboardingScreen()
    }
}

@Composable
private fun GalaxyOnboardingScreen() {
    val vm: OnboardingViewModel = pigeonVm { c, _ -> OnboardingViewModel(c.authRepository) }
    val state by vm.state.collectAsState()

    BackHandler(enabled = state.step != OnboardingStep.Welcome) { vm.goTo(OnboardingStep.Welcome) }

    val nova = LocalExperimentalRedesign.current
    val accent = MaterialTheme.colorScheme.primary
    val lavender = MaterialTheme.colorScheme.secondary
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (nova) {

                    // bottom-right) that slowly breathes behind the whole flow —
                    // the first impression, so it should feel alive, not flat.
                    Modifier.novaAuroraBackground(accent, cyan = lavender, animate = true)
                } else {
                    // soft ambient wash: accent glow top-leading, lavender bottom-trailing
                    Modifier.drawBehind {
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
                },
            )
            .systemBarsPadding()
            .imePadding(),
    ) {
        if (state.step != OnboardingStep.Welcome) {
            if (nova) {

                Box(
                    Modifier
                        .padding(Spacing.m)
                        .size(44.dp)
                        .clip(NovaCorners.iconBadge)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = NovaDepth.rimTop),
                                    accent.copy(alpha = NovaDepth.rimBottom),
                                ),
                            ),
                            NovaCorners.iconBadge,
                        )
                        .clickable { vm.goTo(OnboardingStep.Welcome) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                IconButton(onClick = { vm.goTo(OnboardingStep.Welcome) }, modifier = Modifier.padding(Spacing.s)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
    val cyan = MaterialTheme.colorScheme.secondary
    val nova = LocalExperimentalRedesign.current
    val glyphSize = if (nova) 140.dp else 112.dp

    val pulse = rememberNovaPulse(periodMillis = 3400)
    val reduced = LocalReducedMotion.current
    val floatDy = if (nova && !reduced) (pulse - 0.5f) * 10f else 0f
    Box(
        Modifier
            .then(if (nova) Modifier.heroAppear() else Modifier)
            .graphicsLayer { translationY = floatDy.dp.toPx() }
            // soft accent halo pooled under the glyph so it lifts off the void
            .then(if (nova) Modifier.novaHalo(accent, alpha = 0.14f + 0.10f * pulse) else Modifier)
            .size(glyphSize)
            .then(
                if (nova) Modifier.background(
                    // dual-accent identity from the very first screen: iris -> cyan
                    Brush.linearGradient(listOf(accent, cyan)),
                    CircleShape,
                ) else Modifier.background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ),
                    CircleShape,
                ),
            )
            .background(Brush.radialGradient(listOf(accent.copy(alpha = if (nova) 0.0f else 0.16f), Color.Transparent)), CircleShape)
            .border(
                if (nova) 2.dp else 1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = if (nova) 0.45f else 0.30f), Color.Transparent, accent.copy(alpha = 0.25f))),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // foreground asset keeps adaptive-icon padding, so scale the glyph back up
        Image(
            painterResource(app.pigeonsms.R.mipmap.ic_fg_icon),
            contentDescription = null,
            modifier = Modifier.size(glyphSize).graphicsLayer { scaleX = 1.55f; scaleY = 1.55f },
        )
    }
    Spacer(Modifier.height(Spacing.xl))
    Text(
        "pigeonsms",
        style = if (nova) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displaySmall,
        letterSpacing = if (nova) (-1.6).sp else (-0.5).sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = if (nova) Modifier.heroAppear(delayMillis = 90) else Modifier,
    )
    Text(
        "your flock's cozy corner",
        style = if (nova) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.s).then(if (nova) Modifier.heroAppear(delayMillis = 150) else Modifier),
    )
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
    val nova = LocalExperimentalRedesign.current
    Text(
        title,
        style = if (nova) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge,
        letterSpacing = if (nova) (-1.3).sp else (-0.5).sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = if (nova) Modifier.heroAppear() else Modifier,
    )
    if (sub.isNotBlank()) Text(
        sub,
        style = if (nova) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.s).then(if (nova) Modifier.heroAppear(delayMillis = 80) else Modifier),
    )
    Spacer(Modifier.height(Spacing.xl))
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, keyboardType: KeyboardType = KeyboardType.Text, isPassword: Boolean = false) {
    val nova = LocalExperimentalRedesign.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()

    val novaFocusBorder by androidx.compose.animation.animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
        label = "fieldFocus",
    )
    OutlinedTextField(
        value = value, onValueChange = onChange,
        interactionSource = source,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true, shape = if (nova) NovaCorners.input else Corners.input,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = if (nova) novaFocusBorder else MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = if (nova) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = if (nova) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Primary(text: String, loading: Boolean, onClick: () -> Unit) {
    if (LocalExperimentalRedesign.current) {

        // spring press-scale — the most-tapped control finally feels premium and

        Box(Modifier.fillMaxWidth().height(Dimens.ctaHeight + 8.dp)) {
            NovaPillButton(
                text = text,
                onClick = { if (!loading) onClick() },
                armed = !loading,
                height = Dimens.ctaHeight + 8.dp,
                modifier = Modifier.fillMaxWidth().heroAppear(delayMillis = 60),
                leading = if (loading) {
                    { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else null,
            )
        }
    } else {
        Button(
            onClick = onClick, enabled = !loading, shape = Corners.button,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            modifier = Modifier.fillMaxWidth().height(Dimens.ctaHeight),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Err(error: String?) {
    if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.s))
}

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
private fun ExOnboardingScreen() {
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
                OnboardingStep.Welcome -> ExWelcome(vm)
                OnboardingStep.Invite -> ExInvite(vm, state)
                OnboardingStep.Details -> ExDetails(vm, state)
                OnboardingStep.Login -> ExLogin(vm, state)
            }
        }
    }
}

@Composable
private fun ExStep(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.xl), verticalArrangement = Arrangement.Center, content = content)
}

@Composable
private fun ExWelcome(vm: OnboardingViewModel) = ExStep {
    val accent = MaterialTheme.colorScheme.primary
    val glyphSize = 140.dp
    Box(
        Modifier
            .size(glyphSize)
            .background(
                Brush.linearGradient(listOf(accent, MaterialTheme.colorScheme.tertiary)),
                CircleShape,
            )
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.0f), Color.Transparent)), CircleShape)
            .border(
                2.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent, accent.copy(alpha = 0.25f))),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // foreground asset keeps adaptive-icon padding, so scale the glyph back up
        Image(
            painterResource(app.pigeonsms.R.mipmap.ic_fg_icon),
            contentDescription = null,
            modifier = Modifier.size(glyphSize).graphicsLayer { scaleX = 1.55f; scaleY = 1.55f },
        )
    }
    Spacer(Modifier.height(Spacing.xl))
    Text(
        "pigeonsms",
        style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp, lineHeight = 56.sp),
        letterSpacing = (-0.5).sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text("your flock's cozy corner", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.s))
    Spacer(Modifier.height(Spacing.huge))
    ExPrimary("i have an invite", false) { vm.goTo(OnboardingStep.Invite) }
    TextButton(onClick = { vm.goTo(OnboardingStep.Login) }, modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)) {
        Text("i already have an account", color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun ExInvite(vm: OnboardingViewModel, s: OnboardingUiState) = ExStep {
    ExHead("your invite", "pigeonsms is invite-only. ask a friend for a code.")
    ExField(s.invite, vm::setInvite, "PGN-XXXX-XXXX")
    Err(s.error); Spacer(Modifier.height(Spacing.l)); ExPrimary("continue", s.loading, vm::submitInvite)
}

@Composable
private fun ExDetails(vm: OnboardingViewModel, s: OnboardingUiState) = ExStep {
    ExHead("make it yours", "pick a name your friends will recognize.")
    ExField(s.username, vm::setUsername, "username"); Spacer(Modifier.height(Spacing.m))
    ExField(s.email, vm::setEmail, "email", KeyboardType.Email); Spacer(Modifier.height(Spacing.m))
    ExField(s.password, vm::setPassword, "password", isPassword = true)
    Err(s.error); Spacer(Modifier.height(Spacing.l)); ExPrimary("create account", s.loading, vm::submitSignup)
}

@Composable
private fun ExLogin(vm: OnboardingViewModel, s: OnboardingUiState) = ExStep {
    ExHead("welcome back", "")
    ExField(s.loginField, vm::setLoginField, "username or email"); Spacer(Modifier.height(Spacing.m))
    ExField(s.loginPassword, vm::setLoginPassword, "password", isPassword = true)
    if (s.needsTotp) { Spacer(Modifier.height(Spacing.m)); ExField(s.totp, vm::setTotp, "2fa code", KeyboardType.Number) }
    Err(s.error); Spacer(Modifier.height(Spacing.l)); ExPrimary("sign in", s.loading, vm::submitLogin)
}

@Composable
private fun ColumnScope.ExHead(title: String, sub: String) {
    Text(
        title,
        style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 44.sp),
        letterSpacing = (-0.5).sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.s))
    Spacer(Modifier.height(Spacing.xl))
}

@Composable
private fun ExField(value: String, onChange: (String) -> Unit, placeholder: String, keyboardType: KeyboardType = KeyboardType.Text, isPassword: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true, shape = NovaCorners.input,
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
private fun ExPrimary(text: String, loading: Boolean, onClick: () -> Unit) {

    Box(
        Modifier
            .fillMaxWidth()
            .height(Dimens.ctaHeight + 8.dp)
            .clip(NovaCorners.button)
            .background(
                Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                ),
            )
            .then(if (!loading) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
}
