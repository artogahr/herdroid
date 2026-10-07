package dev.herdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.herdroid.data.ConnectionService
import dev.herdroid.data.session
import dev.herdroid.ui.App
import dev.herdroid.ui.theme.HerdroidTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        ConnectionService.stop(this)
        (application as HerdroidApp).connection.onForeground()
    }

    override fun onStop() {
        super.onStop()
        val connection = (application as HerdroidApp).connection
        if (connection.state.value.session != null && !isChangingConfigurations) ConnectionService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val connection = (application as HerdroidApp).connection
        setContent {
            HerdroidTheme {
                App(connection)
            }
        }
    }
}
