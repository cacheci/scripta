package top.yukonga.scripta.sandbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import top.yukonga.scripta.sandbox.spike.SpikeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Spike phase: prove the two highest risks (self-managed IME + cross-line selection)
            // before building the full editor. The placeholder CodeEditor is intentionally not shown.
            Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                SpikeScreen()
            }
        }
    }
}
