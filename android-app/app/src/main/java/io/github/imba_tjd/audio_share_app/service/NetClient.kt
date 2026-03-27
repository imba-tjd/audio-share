package io.github.imba_tjd.audio_share_app.service

sealed interface NetClient {
    suspend fun stop()

    var onError: ((String) -> Unit)?
}