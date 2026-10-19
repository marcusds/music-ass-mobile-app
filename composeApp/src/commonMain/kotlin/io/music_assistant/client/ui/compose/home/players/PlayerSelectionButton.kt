// Compose layout values (sizes, alphas, animation durations) are visual design tokens.
@file:Suppress("MagicNumber")

package io.music_assistant.client.ui.compose.home.players

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.music_assistant.client.data.LocalPlayerStatus
import io.music_assistant.client.data.model.client.PlayerData
import io.music_assistant.client.data.model.client.PlayerDataFixtures
import org.jetbrains.compose.resources.painterResource

@Composable
fun PlayerSelectionButton(
    player: PlayerData,
    controlTint: Color = MaterialTheme.colorScheme.primary,
    sendSpinState: LocalPlayerStatus?,
    onSelectPlayer: () -> Unit = {},
) {
    val isLocalPlayer = player.isLocal
    val dotColor = (if (isLocalPlayer) sendSpinState else null)?.toDotColor()

    val playerContentDescription = player.contentDescription()
    val modifier = Modifier.clearAndSetSemantics {
        contentDescription = playerContentDescription
    }

    OutlinedButton(
        modifier = modifier,
        onClick = onSelectPlayer,
    ) {
        PlayerButtonContent(
            player = player,
            controlTint = controlTint,
            dotColor = dotColor,
        )
    }
}

@Composable
private fun PlayerButtonContent(
    player: PlayerData,
    controlTint: Color,
    dotColor: Color?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(player.iconRes()),
            modifier = Modifier.size(16.dp),
            tint = controlTint,
            contentDescription = null,
        )

        dotColor?.let {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(it, CircleShape),
            )
        }

        val playerLabel = player.label()

        Text(
            text = playerLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun LocalPlayerStatus.toDotColor(): Color = when (this) {
    LocalPlayerStatus.Connected -> Color(0xFF4CAF50) // Green
    LocalPlayerStatus.Connecting, LocalPlayerStatus.Reconnecting -> Color(0xFFFF9800) // Orange
    LocalPlayerStatus.Failed -> Color(0xFFF44336) // Red
}

@Preview
@Composable
private fun PlayerSelectionButtonNotGroupablePreview() {
    MaterialTheme {
        PlayerSelectionButton(
            player = PlayerDataFixtures.playerData(),
            sendSpinState = null,
        )
    }
}

@Preview
@Composable
private fun PlayerSelectionButtonLocalPlayerPreview() {
    MaterialTheme {
        PlayerSelectionButton(
            player = PlayerDataFixtures.playerData().copy(isLocal = true),
            sendSpinState = LocalPlayerStatus.Connecting,
        )
    }
}

@Preview
@Composable
private fun PlayerSelectionButtonPreview() {
    MaterialTheme {
        PlayerSelectionButton(
            player = PlayerDataFixtures.playerData(
                groupChildren = listOf(PlayerDataFixtures.bind().copy(isBound = false)),
            ),
            sendSpinState = null,
        )
    }
}
