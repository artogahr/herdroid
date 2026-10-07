package dev.herdroid

import android.app.Application
import dev.herdroid.core.transport.SshKeys
import dev.herdroid.data.Connection
import dev.herdroid.data.ConnectionService
import dev.herdroid.data.session
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
        MainScope().launch {
            // Reconnecting keeps a session, so the service stays up while the link recovers.
            connection.state.map { it.session != null }.distinctUntilChanged().collect { linked ->
                if (!linked) ConnectionService.stop(this@HerdroidApp)
            }
        }
        connection.autoConnect()
    }
}
