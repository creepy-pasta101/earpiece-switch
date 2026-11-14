package dev.creepypasta.earpiece.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.creepypasta.earpiece.R
import dev.creepypasta.earpiece.model.AudioRoute

/** String resource for the display name of a route. */
fun AudioRoute.labelRes(): Int = when (this) {
    AudioRoute.EARPIECE -> R.string.route_earpiece
    AudioRoute.SPEAKER -> R.string.route_speaker
    AudioRoute.BLUETOOTH -> R.string.route_bluetooth
    AudioRoute.WIRED -> R.string.route_wired
}

/** String resource for the one-line explanation of a route. */
fun AudioRoute.descriptionRes(): Int = when (this) {
    AudioRoute.EARPIECE -> R.string.route_earpiece_desc
    AudioRoute.SPEAKER -> R.string.route_speaker_desc
    AudioRoute.BLUETOOTH -> R.string.route_bluetooth_desc
    AudioRoute.WIRED -> R.string.route_wired_desc
}

private fun AudioRoute.icon(): ImageVector = when (this) {
    AudioRoute.EARPIECE -> Icons.Rounded.Headset
    AudioRoute.SPEAKER -> Icons.Rounded.VolumeUp
    AudioRoute.BLUETOOTH -> Icons.Rounded.Bluetooth
    AudioRoute.WIRED -> Icons.Rounded.Cable
}

/**
 * One selectable output route.
 *
 * Stateless on purpose: everything it renders is derived from its arguments, so
 * it can be previewed and reused without a view model. The card is not given a
 * click handler through `Card(onClick = ...)`; instead the whole surface is made
 * `selectable`, which gives it the correct "selected" semantics for a radio-like
 * group and disables it cleanly when the route is unavailable.
 */
@Composable
fun RouteCard(
    route: AudioRoute,
    available: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = available,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 0.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                // Unavailable routes stay readable but visibly inert.
                .alpha(if (available) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = route.icon(),
                contentDescription = null,
                tint = LocalContentColor.current,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(route.labelRes()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(route.descriptionRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    // Derived from the card's content colour, so the description
                    // stays legible on both the normal and selected containers.
                    color = LocalContentColor.current.copy(alpha = 0.78f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!available) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.state_unavailable),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (selected) {
                Spacer(modifier = Modifier.width(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircleOutline,
                        contentDescription = stringResource(R.string.cd_active_route),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.state_active),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
