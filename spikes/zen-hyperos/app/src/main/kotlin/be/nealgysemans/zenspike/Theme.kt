package be.nealgysemans.zenspike

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Plain (non-dynamic) Material 3 colours. Dynamic colour is deliberately avoided:
 * the spike toggles the system night-mode device effect, and a fixed palette makes
 * "did night mode actually apply?" easier to judge from the app's own surface.
 */
@Composable
fun SpikeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
