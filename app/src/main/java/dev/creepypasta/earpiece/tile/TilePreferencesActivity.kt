package dev.creepypasta.earpiece.tile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.creepypasta.earpiece.R
import dev.creepypasta.earpiece.model.AudioRoute
import dev.creepypasta.earpiece.service.AudioRouteService
import dev.creepypasta.earpiece.state.RouteState
import dev.creepypasta.earpiece.ui.theme.EarpieceTheme

/**
 * Opened by long-pressing the Quick Settings tile.
 *
 * The system launches whichever activity declares the
 * `android.service.quicksettings.action.QS_TILE_PREFERENCES` intent-filter, which is what
 * replaces the default "App info" long-press sheet. The screen is deliberately tiny: pick a
 * route, the service is asked to hold it, the screen closes.
 */
class TilePreferencesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            EarpieceTheme {
                // The tile and the main screen write the same StateFlow, so the check mark
                // follows whatever was picked last anywhere in the app.
                val requested by RouteState.requested.collectAsStateWithLifecycle()
                TilePreferencesScreen(
                    requested = requested,
                    onRouteSelected = { route -> applyAndFinish(route) },
                )
            }
        }
    }

    /**
     * Asks [AudioRouteService] to hold [route] and closes the screen either way: a refusal is
     * logged, never surfaced as a crash — a preferences activity that dies on a background
     * start restriction is worse than one that quietly does nothing.
     */
    private fun applyAndFinish(route: AudioRoute) {
        try {
            AudioRouteService.apply(this, route)
        } catch (e: SecurityException) {
            Log.w(TAG, "Route service refused to start", e)
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException on API 31+, or a missing
            // MODIFY_AUDIO_SETTINGS grant: log and close rather than take the process down.
            Log.w(TAG, "Route service could not start", e)
        }
        finish()
    }

    private companion object {
        const val TAG = "Earpiece"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TilePreferencesScreen(
    requested: AudioRoute,
    onRouteSelected: (AudioRoute) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.tile_prefs_title)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            AudioRoute.all.forEach { route ->
                RouteRow(
                    route = route,
                    selected = route == requested,
                    onClick = { onRouteSelected(route) },
                )
            }
        }
    }
}

@Composable
private fun RouteRow(
    route: AudioRoute,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(stringResource(routeLabelRes(route))) },
        supportingContent = { Text(stringResource(routeDescriptionRes(route))) },
        // The slot is always present, so selecting a route never re-measures the row.
        trailingContent = {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.cd_active_route),
                )
            }
        },
    )
}

private fun routeLabelRes(route: AudioRoute): Int = when (route) {
    AudioRoute.EARPIECE -> R.string.route_earpiece
    AudioRoute.SPEAKER -> R.string.route_speaker
    AudioRoute.BLUETOOTH -> R.string.route_bluetooth
    AudioRoute.WIRED -> R.string.route_wired
    else -> R.string.route_earpiece
}

private fun routeDescriptionRes(route: AudioRoute): Int = when (route) {
    AudioRoute.EARPIECE -> R.string.route_earpiece_desc
    AudioRoute.SPEAKER -> R.string.route_speaker_desc
    AudioRoute.BLUETOOTH -> R.string.route_bluetooth_desc
    AudioRoute.WIRED -> R.string.route_wired_desc
    else -> R.string.route_earpiece_desc
}
