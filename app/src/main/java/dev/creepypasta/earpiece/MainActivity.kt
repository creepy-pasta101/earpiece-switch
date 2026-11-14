package dev.creepypasta.earpiece

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.creepypasta.earpiece.ui.MainScreen
import dev.creepypasta.earpiece.ui.MainViewModel
import dev.creepypasta.earpiece.ui.theme.EarpieceTheme

/**
 * Single-activity host. The activity is deliberately thin: it owns no state, it
 * just collects [MainViewModel.state] lifecycle-aware and wires the screen's
 * callbacks to it.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars before the first frame, so the theme's
        // system-bar icon appearance is the only thing left to manage.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            EarpieceTheme {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                MainScreen(
                    state = state,
                    onSelect = viewModel::select,
                    onToggleService = viewModel::toggleService,
                    onToggleTone = viewModel::toggleTestTone,
                    onRestoreOnBootChange = viewModel::setRestoreOnBoot,
                    onMessageShown = viewModel::consumeMessage,
                )
            }
        }
    }
}
