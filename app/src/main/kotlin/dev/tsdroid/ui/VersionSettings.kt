package dev.tsdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tsdroid.core.Native

/**
 * The announced client version: a version number plus a platform.
 *
 * The server compares the announced `client_version` + `client_platform` with
 * `client_version_sign` byte for byte and refuses a mismatch with
 * `ClientHacked`. The signature is TeamSpeak's, bound to the exact (platform,
 * full version text) pair, so a free-text version can only fail: what is
 * pickable is which signed pair to present.
 *
 * Both lists come from the native table and are filtered there, so the two
 * dropdowns cannot be combined into an impossible pair: the version list is
 * built *for the selected platform*, and changing the platform re-selects a
 * version from that platform's own list. There is no state in which the field
 * holds a number the platform has no signature for.
 */
@Composable
fun VersionSettings(
    version: String,
    platform: String,
    onChoose: (version: String, platform: String) -> Unit,
) {
    val platforms = remember { Native.nativeVersionPlatforms().split(',').filter { it.isNotBlank() } }
    val versions = remember(platform) {
        Native.nativeVersionChoices(platform).split(',').filter { it.isNotBlank() }
    }
    val resolved = remember(version, platform) { Native.nativeVersionResolve(version, platform) }

    // The pair is committed in one step, never field by field. Two separate
    // callbacks would pass through an impossible combination on the way —
    // switching the platform while keeping the old number — and a validity
    // check on each would reject the first half and leave the platform unchanged.
    fun choosePlatform(next: String) {
        val nextVersions = Native.nativeVersionChoices(next).split(',').filter { it.isNotBlank() }
        // Keep the number when the new platform signs it; otherwise take that
        // platform's newest release.
        val landing = nextVersions.firstOrNull { it == version } ?: nextVersions.firstOrNull()
        if (landing != null) onChoose(landing, next)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceField(
            label = "版本号",
            value = version,
            options = versions,
            emptyLabel = "选择版本",
            onChoose = { onChoose(it, platform) },
        )

        ChoiceField(
            label = "平台",
            value = platform,
            options = platforms,
            emptyLabel = "选择平台",
            onChoose = ::choosePlatform,
        )

        // Fixed one-line slot: the content swaps, the height does not.
        Text(
            text = if (resolved.isNotEmpty()) "将声明为：$resolved" else "该版本在此平台无签名",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (resolved.isNotEmpty()) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "改动在下次连接时生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One labelled dropdown: the current value in a row, the options in a menu.
 *
 * Both fields of this screen are the same control, so they are the same code.
 * The list is whatever the caller passes — already filtered to what is valid
 * for the other field — which is what keeps the two from combining into a pair
 * the server would refuse.
 */
@Composable
private fun ChoiceField(
    label: String,
    value: String,
    options: List<String>,
    emptyLabel: String,
    onChoose: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value.ifEmpty { emptyLabel },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    trailingIcon = {
                        if (option == value) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    onClick = {
                        onChoose(option)
                        open = false
                    },
                )
            }
        }
    }
}
