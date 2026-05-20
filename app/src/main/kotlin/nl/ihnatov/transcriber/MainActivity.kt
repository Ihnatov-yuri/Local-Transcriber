package nl.ihnatov.transcriber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import nl.ihnatov.transcriber.ui.nav.AppNav
import nl.ihnatov.transcriber.ui.theme.TranscriberTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TranscriberApplication).container
        setContent {
            TranscriberTheme {
                AppNav(container)
            }
        }
    }
}
