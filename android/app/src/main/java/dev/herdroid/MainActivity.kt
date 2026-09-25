package dev.herdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.herdroid.ui.App
import dev.herdroid.ui.theme.HerdroidTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as HerdroidApp).connection.onForeground()
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
