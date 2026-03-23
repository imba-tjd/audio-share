package io.github.imba_tjd.audio_share_app.ui.screen

import android.widget.RadioGroup
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import io.github.imba_tjd.audio_share_app.service.DiscoverClient
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
                        onSave: (proto: String, host: String, port: Int, use_opus: Boolean, opus_skip: Int) -> Unit,
                        getMediaController: suspend () -> MediaController
                        ) {
    val scope = rememberCoroutineScope()

    var host by remember(uiState) { mutableStateOf(uiState.host) }
    var port by remember(uiState) { mutableStateOf(uiState.port.toString()) }
    var proto by remember(uiState) { mutableStateOf(uiState.proto) }
    var use_opus by remember(uiState) { mutableStateOf(uiState.use_opus) }
    var opus_skip by remember(uiState) { mutableStateOf(uiState.opus_skip) }

    var started by remember { mutableStateOf(false) }
    val isHostError by remember { derivedStateOf {
        host.isEmpty()
    } }
    val isPortError by remember { derivedStateOf {
        port.isEmpty()
    } }
    val discoverclient by remember { mutableStateOf(DiscoverClient()) }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface() {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                arrayOf("UDP", "TCP").forEach { p ->
                    Row (verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(!started) { proto = p }
                        ) {
                        RadioButton(
                            selected = proto == p,
                            onClick = { proto = p }
                        )
                        Text(p)
                        Spacer(Modifier.size(16.dp))
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(!started) {use_opus = !use_opus}
            ) {
                Checkbox(use_opus, onCheckedChange = {use_opus = it}, enabled = !started)
                Text("Use Opus")
                Spacer(Modifier.width(16.dp))
            }

            Spacer(Modifier.width(16.dp))

            OutlinedTextField(opus_skip.toString(), onValueChange = {opus_skip = it.toIntOrNull() ?: 0},
                label = { Text("Opus skip value") },
                enabled = !started && use_opus
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
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
                singleLine = true
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
                singleLine = true
            )
        }

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val probing_text = stringResource(R.string.probing)
            val found_text = stringResource(R.string.probe_found)

            IconButton({
                scope.launch {
                    AudioPlayer.message = probing_text
                    val server = discoverclient.probe()
                    server.onSuccess {
                        proto = it.proto
                        host = it.address.hostname
                        port = it.address.port.toString()
                        use_opus = it.useOpus
                        opus_skip = it.opusSkip
                        AudioPlayer.message = "${found_text} ${it.address.hostname}"
                    }.onFailure {
                        AudioPlayer.message = it.toString()
                    }
                }
            }, Modifier.size(48.dp)){
                Icon(
                    imageVector = Icons.Default.WifiTethering,
                    contentDescription = "probe",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.fillMaxSize()
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
                                onSave(proto, host, port.toInt(), use_opus, opus_skip)
                                getMediaController().play() // 调用PlaybackService，会转发给player，调用playWhenReady(true)
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
                    contentDescription = "Play or pause",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        OutlinedCard(modifier = Modifier.weight(1f)) {
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
        HomeScreenStateless(uiState, { _, _, _, _, _ -> }, { TODO() })
    }
}
