package com.caleb.prism

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder

internal object CapturePreflight {
    fun warning(context: Context, source: AudioSource, ownSession: Int?): String? {
        if (source == AudioSource.SYSTEM) return "Android may stop another app's screen-share or device-audio session when prism connects. prism captures audio only, without screen images."
        val recordings = runCatching { context.getSystemService(AudioManager::class.java).activeRecordingConfigurations }.getOrDefault(emptyList())
        val anotherMic = recordings.any {
            it.clientAudioSessionId != ownSession && isMicrophone(it.clientAudioSource)
        }
        return if (anotherMic) "Another app is using microphone capture. Continuing may interrupt its audio or leave prism muted."
            else null
    }

    // Playback/remote-submix (8) is generally omitted by Android, and is not a mic
    // conflict on devices which do expose it. Never infer a projection owner from this list.
    internal fun isMicrophone(source: Int) = source in setOf(
        MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.VOICE_PERFORMANCE
    )
}
