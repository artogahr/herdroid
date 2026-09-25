package dev.herdroid

import android.app.Application
import dev.herdroid.core.transport.SshKeys
import dev.herdroid.data.Connection

class HerdroidApp : Application() {
    lateinit var connection: Connection
        private set
    lateinit var ui: dev.herdroid.data.UiPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        SshKeys.installProvider()
        dev.herdroid.thread.Latency.enabled = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        connection = Connection(this)
        ui = dev.herdroid.data.UiPrefs(this)
        connection.autoConnect()
    }
}
