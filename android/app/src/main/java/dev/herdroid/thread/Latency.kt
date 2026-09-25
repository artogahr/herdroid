package dev.herdroid.thread

import android.util.Log

/** Timestamps for latency measurements from adb (`adb logcat -s HerdroidLatency`); debug only. */
internal object Latency {
    var enabled = false

    fun log(
        stage: String,
        text: String?,
    ) {
        if (enabled) Log.d("HerdroidLatency", "$stage ${System.currentTimeMillis()} ${text.orEmpty().take(48).replace('\n', ' ')}")
    }
}
