package dev.creepypasta.earpiece

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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

                // On Android 13+ the foreground service's ongoing notification is
                // suppressed unless the user grants POST_NOTIFICATIONS, which makes
                // a held route look like a dead app. Ask once, at first launch.
                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { _ ->
                    // Deliberately ignored: a denial only hides the ongoing
                    // notification, the route itself still works, and re-asking
                    // here would loop on a permanent denial.
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                MainScreen(
                    state = state,
                    onSelect = viewModel::select,
                    onToggleService = viewModel::toggleService,
                    onToggleTone = viewModel::toggleTestTone,
                    onRestoreOnBootChange = viewModel::setRestoreOnBoot,
                    onMessageShown = viewModel::consumeMessage,
                    onOutcomeShown = viewModel::consumeOutcome,
                )
            }
        }
    }
}
