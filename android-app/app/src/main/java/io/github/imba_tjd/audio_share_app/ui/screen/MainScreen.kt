package io.github.imba_tjd.audio_share_app.ui.screen

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.imba_tjd.audio_share_app.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

sealed class Route(@field:StringRes val labelId: Int, val icon: ImageVector) {
    data object Home : Route(R.string.label_home, Icons.Default.Home)

    data object Audio : Route(R.string.label_audio, Icons.Default.Audiotrack)

    data object Settings : Route(R.string.label_settings, Icons.Default.Settings)
}

@Composable
fun MainScreen() {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    BoxWithConstraints {
        if (isLandscape && maxHeight >= 600.dp && maxHeight >= 400.dp) {
            Surface {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { HomeScreen() }
                    VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 16.dp))
                    Box(Modifier.weight(1f)) { AudioScreen() }
                }
            }
        } else {
            PhoneLayout()
        }
    }
}

@Composable
fun PhoneLayout() {
    val routes = listOf(Route.Home, Route.Audio, Route.Settings)
    var selectedTab: Route by remember { mutableStateOf(Route.Home) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                routes.forEach { rt ->
                    NavigationBarItem(
                        selected = selectedTab == rt,
                        onClick = { selectedTab = rt },
                        icon = { Icon(rt.icon, null) },
                        label = { Text(stringResource(rt.labelId)) }
                    )
                }
            }
        },
    ) { innerPadding ->
        Crossfade(selectedTab, modifier = Modifier.padding(innerPadding)) { rt ->
            when (rt) {
                is Route.Home -> HomeScreen()
                is Route.Audio -> AudioScreen()
                is Route.Settings -> SettingsScreen()
            }
        }
    }
}

@Preview
@Composable
fun MainScreenPreview() {
    MainScreen()
}
