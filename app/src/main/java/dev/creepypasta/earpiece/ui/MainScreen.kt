package dev.creepypasta.earpiece.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.creepypasta.earpiece.BuildConfig
import dev.creepypasta.earpiece.R
import dev.creepypasta.earpiece.model.AudioRoute
import dev.creepypasta.earpiece.model.RouteResolver
import dev.creepypasta.earpiece.ui.components.RouteCard
import dev.creepypasta.earpiece.ui.components.labelRes

/**
 * The whole app in one screen: the four output routes, the hold/release and test
 * tone tools, one preference, and a short about footer.
 *
 * Stateless: every value comes from [state] and every interaction leaves through
 * a callback, so the screen can be previewed with a hand-made state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onSelect: (AudioRoute) -> Unit,
    onToggleService: () -> Unit,
    onToggleTone: () -> Unit,
    onRestoreOnBootChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onMessageShown: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message
    // Resolve the text in composition; the effect below is not a composable scope.
    val messageText = message?.let { stringResource(it.textRes, *it.args.toTypedArray()) }

    LaunchedEffect(message?.id) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            // Consume only after the snackbar has actually been shown, so a
            // reconfiguration in between does not lose the message.
            onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(textRes = R.string.section_output)

            if (state.requested != state.active) {
                Text(
                    text = stringResource(
                        R.string.downgrade_note,
                        stringResource(state.requested.labelRes()),
                        stringResource(state.active.labelRes()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            AudioRoute.all.forEach { route ->
                // A route is available exactly when resolving it needs no
                // downgrade; that reuses the same rules the audio layer applies.
                val available = !RouteResolver.resolve(route, state.capabilities).downgraded
                RouteCard(
                    route = route,
                    available = available,
                    selected = route == state.active,
                    onClick = { onSelect(route) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionLabel(textRes = R.string.section_tools)

            FilledTonalButton(
                onClick = onToggleService,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (state.serviceRunning) R.string.action_release_route else R.string.action_hold_route,
                    ),
                )
            }
            Text(
                text = stringResource(
                    if (state.serviceRunning) R.string.state_holding else R.string.state_not_holding,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )

            OutlinedButton(
                onClick = onToggleTone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (state.tonePlaying) R.string.action_stop_tone else R.string.action_test_tone,
                    ),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.pref_restore_on_boot),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.pref_restore_on_boot_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = state.restoreOnBoot,
                    onCheckedChange = onRestoreOnBootChange,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Text(
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.about_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small all-caps-ish section header, tinted with the primary role. */
@Composable
private fun SectionLabel(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}
