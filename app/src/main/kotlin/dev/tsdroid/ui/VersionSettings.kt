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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
 * full version text) pair, so the user cannot type a version string that works —
 * what they pick is a **version number** and a **platform**, and the native side
 * resolves the pair to the full text (with its `[Build: …]` suffix) and the
 * signature.
 *
 * Both lists come from the native table: [Native.nativeVersionPlatforms] for the
 * platforms that have a signature at all, and [Native.nativeVersionSuggest] for
 * the version numbers a platform offers. A choice that resolves to nothing is
 * shown as an error and cannot be saved, because it could only end in
 * `ClientHacked` — and repeated attempts on an official server get the IP banned.
 *
 * The resolved text is shown under the field so the build suffix the user never
 * typed is visible before it goes out.
 */
@Composable
fun VersionSettings(
    version: String,
    platform: String,
    onVersionChange: (String) -> Unit,
    onPlatformChange: (String) -> Unit,
) {
    val platforms = remember { Native.nativeVersionPlatforms().split(',').filter { it.isNotBlank() } }
    val resolved = remember(version, platform) { Native.nativeVersionResolve(version, platform) }
    val suggestions = remember(version, platform) {
        Native.nativeVersionSuggest(version, platform).split(',').filter { it.isNotBlank() }
    }

    var versionMenu by remember { mutableStateOf(false) }
    var platformMenu by remember { mutableStateOf(false) }
    val valid = resolved.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Version number: free text, matched against the table as it is typed.
        Box {
            OutlinedTextField(
                value = version,
                onValueChange = {
                    onVersionChange(it)
                    versionMenu = true
                },
                label = { Text("版本号") },
                placeholder = { Text("如 3.6.3") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                isError = !valid,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = versionMenu && suggestions.isNotEmpty(),
                onDismissRequest = { versionMenu = false },
                modifier = Modifier.heightIn(max = 240.dp),
            ) {
                suggestions.forEach { number ->
                    DropdownMenuItem(
                        text = { Text(number) },
                        onClick = {
                            onVersionChange(number)
                            versionMenu = false
                        },
                    )
                }
            }
        }

        // Platform: only the platforms the table has a signature for.
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { platformMenu = true }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "平台",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = platform.ifEmpty { "选择平台" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(
                expanded = platformMenu,
                onDismissRequest = { platformMenu = false },
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                platforms.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p) },
                        trailingIcon = {
                            if (p == platform) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        onClick = {
                            onPlatformChange(p)
                            platformMenu = false
                        },
                    )
                }
            }
        }

        // Fixed one-line slot: the content swaps, the height does not.
        Text(
            text = if (valid) "将声明为：$resolved" else "该版本在此平台无签名",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (valid) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "只能从签名表里选，改动在下次连接时生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
