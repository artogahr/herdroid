package dev.herdroid

import android.app.Application
import dev.herdroid.core.transport.SshKeys
import dev.herdroid.data.Connection

class HerdroidApp : Application() {
    lateinit var connection: Connection
        private set

    override fun onCreate() {
        super.onCreate()
        SshKeys.installProvider()
        connection = Connection(this)
        connection.autoConnect()
    }
}
