package io.github.imba_tjd.audio_share_app.ui.screen

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import io.github.imba_tjd.audio_share_app.R
import io.github.imba_tjd.audio_share_app.service.AudioPlayer
import io.github.imba_tjd.audio_share_app.MainActivity
import io.github.imba_tjd.audio_share_app.ui.screen.HomeScreenViewModel.UiState
import io.github.imba_tjd.audio_share_app.ui.theme.AppThemeInternal
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(viewModel: HomeScreenViewModel = viewModel()) {
    val activity = LocalActivity.current as MainActivity

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    when (uiState) {
        UiState.Loading -> {}
        is UiState.Success -> {
            HomeScreenStateless(uiState as UiState.Success, onSave = viewModel::saveNetWorkSettings,
                getMediaController = activity::awaitMediaController)
        }
    }
}

@Composable
fun HomeScreenStateless(uiState: UiState.Success,
                        onSave: (proto: String, host: String, port: Int) -> Unit,
                        getMediaController: suspend () -> MediaController
                        ) {
    val scope = rememberCoroutineScope()

    var host by remember(uiState.host) { mutableStateOf(uiState.host) }
    var port by remember(uiState.port) { mutableStateOf(uiState.port.toString()) }
    var started by remember { mutableStateOf(false) }
    val isHostError by remember { derivedStateOf {
        host.isEmpty()
    } }
    val isPortError by remember { derivedStateOf {
        port.isEmpty()
    } }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(
                8.dp,
                Alignment.CenterHorizontally
            ),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = {
                    if (it.isEmpty() || !it.contains(Regex("\\s"))) {
                        host = it
                    }
                },
                enabled = !started,
                isError = isHostError,
                label = { Text(stringResource(R.string.label_host)) },
                modifier = Modifier.weight(0.7f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = {
                    if (it.isEmpty() || it.isDigitsOnly() && it.toInt() in 1..65535) {
                        port = it
                    }
                },
                enabled = !started,
                isError = isPortError,
                label = { Text(stringResource(R.string.label_port)) },
                modifier = Modifier.weight(0.3f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        IconButton(
            onClick = {
                if (isHostError || isPortError) {
                    return@IconButton
                }
                scope.launch {
                    if (started) {
                        getMediaController().stop()
                    } else {
                        try {
                            onSave(uiState.proto, host, port.toInt())
                            getMediaController().play()
                        } catch (_: NumberFormatException) {
                            return@launch
                        }
                    }
                }
            },
            modifier = Modifier.size(80.dp),
        ) {
            Icon(
                imageVector = if (started) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(
            modifier = Modifier.weight(1f)
        ) {
            OutlinedCard(
                modifier = Modifier.fillMaxSize()
            ) {
                SelectionContainer {
                    Text(
                        text = AudioPlayer.message,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                started = playWhenReady
            }
        }

        scope.launch {
            getMediaController().run {
                started = playWhenReady
                addListener(listener)
            }
        }

        onDispose {
            scope.launch {
                getMediaController().removeListener(listener)
            }
        }
    }
}

@Preview
@Composable
private fun HomePrev() {
    val uiState = UiState.Success("UDP", "127.0.0.1", 8888)
    AppThemeInternal {
        HomeScreenStateless(uiState, { _, _, _ -> }, { TODO() })
    }
}
