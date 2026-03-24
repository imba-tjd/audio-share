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

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.imba_tjd.audio_share_app.R
import io.github.imba_tjd.audio_share_app.model.NetworkConfigKeys
import io.github.imba_tjd.audio_share_app.model.getInteger
import io.github.imba_tjd.audio_share_app.model.networkConfigDataStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeScreenViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface UiState {
        data object Loading : UiState
        data class Success(
            val proto: String,
            val host: String,
            val port: Int,
            val use_opus: Boolean = true,
//            val opus_skip: Int = 0,
            val use_jb: Boolean = false,
        ) : UiState
    }

    val uiState: StateFlow<UiState> = application.networkConfigDataStore.data.map {
        UiState.Success(
            proto = it[stringPreferencesKey(NetworkConfigKeys.PROTO)] ?: application.getString(R.string.default_proto),
            host = it[stringPreferencesKey(NetworkConfigKeys.HOST)] ?: application.getString(R.string.default_host),
            port = it[intPreferencesKey(NetworkConfigKeys.PORT)] ?: application.getInteger(R.integer.default_port),
            use_opus = it[booleanPreferencesKey(NetworkConfigKeys.USE_OPUS)] ?: true,
//            opus_skip = it[intPreferencesKey(NetworkConfigKeys.OPUS_SKIP)] ?: 0,
            use_jb = it[booleanPreferencesKey(NetworkConfigKeys.USE_JB)] ?: false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UiState.Loading,
    )

    fun saveNetWorkSettings(proto: String, host: String, port: Int, use_opus: Boolean, use_jb: Boolean): Job {
        return viewModelScope.launch {
            getApplication<Application>().networkConfigDataStore.edit {
                it[stringPreferencesKey(NetworkConfigKeys.PROTO)] = proto
                it[stringPreferencesKey(NetworkConfigKeys.HOST)] = host
                it[intPreferencesKey(NetworkConfigKeys.PORT)] = port
                it[booleanPreferencesKey(NetworkConfigKeys.USE_OPUS)] = use_opus
//                it[intPreferencesKey(NetworkConfigKeys.OPUS_SKIP)] = opus_skip
                it[booleanPreferencesKey(NetworkConfigKeys.USE_JB)] = use_jb
            }
        }
    }
}