package io.github.imba_tjd.audio_share_app.ui.base

import android.content.Context
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.imba_tjd.audio_share_app.R
import io.github.imba_tjd.audio_share_app.model.AudioConfigKeys
import io.github.imba_tjd.audio_share_app.model.audioConfigDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

fun getAvailableEq(): List<String> {
    try{
        val _audioTrack = AudioTrack.Builder().build()
        val sessionId = _audioTrack.audioSessionId;
        val equalizer = Equalizer(0, sessionId)

        val l = mutableListOf<String>()
        for (i in 0 until equalizer.numberOfPresets) {
            val presetName = equalizer.getPresetName(i.toShort())
            l.add(presetName)
        }

        equalizer.release()
        _audioTrack.release()

        return l
    } catch (e: Exception) {
        return listOf("Default")
    }
}

fun currentEq(context: Context): Flow<Int> {
    return context.audioConfigDataStore.data.map {
        it[intPreferencesKey(AudioConfigKeys.EQ_CHOOSE_NDX)] ?: 0
    }
}

suspend fun saveEq(context: Context, choosedEq: Int) {
    context.audioConfigDataStore.edit {
        it[intPreferencesKey(AudioConfigKeys.EQ_CHOOSE_NDX)] = choosedEq
    }
}

@Composable
fun EqualizerPresetSelector() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val availabeEq = remember { getAvailableEq() }
    val currentEq = currentEq(context).collectAsStateWithLifecycle(0)

    EqualizerPresetSelectorStateLess(availabeEq, currentEq.value) {
        scope.launch { saveEq(context, it) }
    }
}

@Composable
fun EqualizerPresetSelectorStateLess(
    presetNames: List<String>, // 从 Equalizer.getPresetName 获取的列表
    currentPresetIndex: Int,   // 从 DataStore 收集到的状态
    onPresetSelected: (Int) -> Unit // 点击回调，执行 DataStore 写入
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = stringResource(R.string.label_eq_presets),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(16.dp, 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            presetNames.forEachIndexed { index, name ->
                val isSelected = currentPresetIndex == index
                FilterChip(
                    selected = isSelected,
                    onClick = { onPresetSelected(index) },
                    label = {
                        if (isSelected) Icon(Icons.Default.Check, "")
                        Text(name)
                    },
                )
            }
        }
    }
}

@Preview
@Composable
private fun EQPrev() {
    var currentEq by remember { mutableIntStateOf(0) }
    val Eqlst = listOf("Default", "High", "Low", "Balanced")

    EqualizerPresetSelectorStateLess(Eqlst, currentEq) {
        currentEq = it
    }
}