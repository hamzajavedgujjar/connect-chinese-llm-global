package ai.norwix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ai.norwix.ui.NorwixApp
import ai.norwix.ui.NorwixViewModel

class MainActivity : ComponentActivity() {
    private val model: NorwixViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NorwixApp(model) }
    }
    override fun onStart() { super.onStart(); model.onForeground() }
    override fun onStop() { model.onBackground(); super.onStop() }
}
