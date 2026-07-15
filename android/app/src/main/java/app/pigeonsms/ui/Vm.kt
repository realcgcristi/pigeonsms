package app.pigeonsms.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.compose.runtime.Composable
import app.pigeonsms.AppContainer
import app.pigeonsms.PigeonApp

class PigeonVmFactory(
    private val build: (AppContainer, CreationExtras) -> ViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = extras[APPLICATION_KEY] as PigeonApp
        return build(app.container, extras) as T
    }
}

@Composable
inline fun <reified VM : ViewModel> pigeonVm(
    key: String? = null,
    noinline build: (AppContainer, CreationExtras) -> ViewModel,
): VM = viewModel(key = key, factory = PigeonVmFactory(build))
