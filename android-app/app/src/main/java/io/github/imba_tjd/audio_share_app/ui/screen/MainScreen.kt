/*
 *    Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.imba_tjd.audio_share_app.ui.screen

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.imba_tjd.audio_share_app.R
import androidx.activity.viewModels

sealed class Route(@field:StringRes val labelId: Int, val icon: ImageVector) {
    data object Home : Route(R.string.label_home, Icons.Default.Home)

    data object Audio : Route(R.string.label_audio, Icons.Default.Audiotrack)

    data object Settings : Route(R.string.label_settings, Icons.Default.Settings)
}

@Composable
fun MainScreen() {
    val routes = listOf(Route.Home, Route.Audio, Route.Settings)
    var selectdTab: Route = Route.Home

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                routes.forEach { rt ->
                    NavigationBarItem(
                        selected = selectdTab == rt,
                        onClick = { selectdTab = rt },
                        icon = { Icon(rt.icon, null) },
                        label = { Text(stringResource(rt.labelId)) }
                    )
                }
            }
        },
    ) { innerPadding ->
        Crossfade(selectdTab, modifier = Modifier.padding(innerPadding)) { rt ->
            when(rt) {
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
