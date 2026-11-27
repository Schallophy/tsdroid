package dev.tsdroid.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tsdroid.SessionUiState
import dev.tsdroid.model.PermissionTarget

/** Choose which group's permission list the editor should open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupTargetPickerSheet(
    ui: SessionUiState,
    onDismiss: () -> Unit,
    onPick: (PermissionTarget) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 20.dp),
        ) {
            item { Text("组权限", style = MaterialTheme.typography.titleLarge) }
            item {
                Text(
                    "服务器组和频道组的权限是分开的两张表。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    "服务器组",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            items(ui.state.serverGroups, key = { "sg${it.id}" }) { group ->
                Text(
                    "服务器组 · ${group.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPick(
                                PermissionTarget(
                                    kind = "server_group",
                                    id = group.id,
                                    title = "服务器组权限 · ${group.name}",
                                ),
                            )
                        }
                        .padding(vertical = 8.dp),
                )
            }
            item {
                Text(
                    "频道组",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            items(ui.state.channelGroups, key = { "cg${it.id}" }) { group ->
                Text(
                    "频道组 · ${group.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPick(
                                PermissionTarget(
                                    kind = "channel_group",
                                    id = group.id,
                                    title = "频道组权限 · ${group.name}",
                                ),
                            )
                        }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}
