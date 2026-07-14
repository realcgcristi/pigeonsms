package app.pigeonsms.design.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable

object PigeonMotion {
    @Composable
    fun <T> snappy(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap()
    else spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow * 2f)

    @Composable
    fun <T> smooth(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap()
    else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)

    @Composable
    fun <T> bouncy(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap()
    else spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
}
