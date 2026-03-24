package io.github.imba_tjd.audio_share_app.model

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ServerInfo(
    val address: InetSocketAddress,
    val proto: String,
    val useOpus: Boolean,
//    val opusSkip: Int
) {
    companion object {
        suspend fun fromDataStore(context: Context): ServerInfo = withContext(Dispatchers.IO) {
            delay(500)
            val conf = context.networkConfigDataStore.data.first()

            return@withContext ServerInfo(
                address = InetSocketAddress(
                    conf[stringPreferencesKey(NetworkConfigKeys.HOST)]!!,
                    conf[intPreferencesKey(NetworkConfigKeys.PORT)]!!
                ),
                proto = conf[stringPreferencesKey(NetworkConfigKeys.PROTO)]!!,
                useOpus = conf[booleanPreferencesKey(NetworkConfigKeys.USE_OPUS)]!!,
//                opusSkip = conf[intPreferencesKey(NetworkConfigKeys.OPUS_SKIP)]!!
            )
        }
    }
}