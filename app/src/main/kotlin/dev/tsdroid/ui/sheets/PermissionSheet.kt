package dev.tsdroid.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.tsdroid.model.PermissionEditorState
import dev.tsdroid.model.PermissionTarget
import dev.tsdroid.model.PermissionValue

/**
 * The channel permission editor.
 *
 * The catalogue comes from the server (`perm_list`) and is shared across targets;
 * the values belong to the channel in the title. Existing entries can be changed
 * or removed, and a catalogue name can be added with a value. Group/client targets
 * still have their own core commands and no sheet yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    target: PermissionTarget,
    state: PermissionEditorState,
    onDismiss: () -> Unit,
    onLoad: (PermissionTarget) -> Unit,
    onAdd: (PermissionTarget, name: String, value: Int) -> Unit,
    onDelete: (PermissionTarget, name: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var addValue by remember { mutableStateOf("1") }
    var editing by remember { mutableStateOf<PermissionValue?>(null) }
    var editValue by remember { mutableStateOf("0") }

    LaunchedEffect(target.kind, target.id) { onLoad(target) }

    val existing = state.values.map { it.name }.toSet()
    val results = state.catalog
        .filter { it.name !in existing }
        .filter {
            query.isBlank() ||
                it.name.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)
        }
        .take(30)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 20.dp),
        ) {
            Text(target.title, style = MaterialTheme.typography.titleLarge)
            Text(
                "权限表来自服务器；值按官方语义发送，-1 表示不限。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                if (state.loading && state.values.isEmpty()) {
                    item { Text("加载中…", style = MaterialTheme.typography.labelMedium) }
                }
                items(state.values, key = { it.name }) { value ->
                    PermissionRow(
                        value = value,
                        onEdit = {
                            editing = value
                            editValue = value.value.toString()
                        },
                        onDelete = { onDelete(target, value.name) },
                    )
                }
                if (results.isNotEmpty()) {
                    item {
                        Text(
                            "添加权限",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("搜索名称或说明") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = addValue,
                            onValueChange = { text ->
                                if (text.all { it.isDigit() } || text.isEmpty() || text == "-") {
                                    addValue = text
                                }
                            },
                            label = { Text("值") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    items(results, key = { "p${it.id}" }) { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAdd(target, entry.name, addValue.toIntOrNull() ?: 0)
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                if (entry.description.isNotBlank()) {
                                    Text(
                                        entry.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(onClick = {
                                onAdd(target, entry.name, addValue.toIntOrNull() ?: 0)
                            }) { Text("添加") }
                        }
                    }
                } else if (query.isBlank() && !state.loading) {
                    item {
                        Text(
                            "权限表还没到；稍后重试或检查服务器权限。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    editing?.let { value ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(value.name) },
            text = {
                OutlinedTextField(
                    value = editValue,
                    onValueChange = { text ->
                        if (text.all { it.isDigit() } || text.isEmpty() || text == "-") {
                            editValue = text
                        }
                    },
                    label = { Text("值") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAdd(target, value.name, editValue.toIntOrNull() ?: 0)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PermissionRow(
    value: PermissionValue,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(value.name, style = MaterialTheme.typography.bodyMedium)
            val suffix = buildString {
                if (value.negated) append(" · negated")
                if (value.skip) append(" · skip")
            }
            Text(
                "值 ${value.value}$suffix",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onEdit) { Text("改") }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}
