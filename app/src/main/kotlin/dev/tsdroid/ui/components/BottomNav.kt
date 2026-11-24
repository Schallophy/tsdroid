package dev.tsdroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tsdroid.NavTab

/**
 * The bottom navigation bar: the app's spine.
 *
 * Five destinations, one per screen. The bar is the only way between them, so
 * every destination is always one tap away — no hidden menus, no nested
 * navigation. The file tab is conditional: file transfer is a minority feature,
 * and leaving it out by default keeps the bar to four clean destinations until
 * the user asks for it in Settings.
 *
 * The bar is drawn on `surfaceContainer` — one step above the page in the
 * near-black palette, so it reads as a fixed shelf the content sits on rather
 * than as another scrolling surface.
 */
private fun NavTab.icon(): ImageVector = when (this) {
    NavTab.Home -> Icons.Filled.Home
    NavTab.Channels -> Icons.AutoMirrored.Filled.ViewList
    NavTab.Chat -> Icons.AutoMirrored.Filled.Chat
    NavTab.Files -> Icons.Filled.Folder
    NavTab.Settings -> Icons.Filled.Settings
}

@Composable
fun BottomNav(
    current: NavTab,
    filesEnabled: Boolean,
    chatUnread: Int,
    onSelect: (NavTab) -> Unit,
) {
    val tabs = if (filesEnabled) NavTab.entries else NavTab.entries.filter { it != NavTab.Files }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // The gesture bar is drawn over the app; the bar must not sit under
            // it, or the labels are half-covered and unreachable.
            .navigationBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        for (tab in tabs) {
            val active = tab == current
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 4.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = tab.icon(),
                            contentDescription = tab.label,
                            tint = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(22.dp),
                        )
                        if (tab == NavTab.Chat && chatUnread > 0) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                                    .padding(start = 10.dp, top = -18.dp),
                            ) {
                                Text(
                                    text = if (chatUnread > 9) "9+" else chatUnread.toString(),
                                    color = MaterialTheme.colorScheme.onError,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
