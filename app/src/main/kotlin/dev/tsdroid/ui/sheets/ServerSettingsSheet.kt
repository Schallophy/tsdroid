package dev.tsdroid.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tsdroid.model.Server

/**
 * The server editor for the fields the core reports back.
 *
 * Optional strings start empty and are only sent when filled, because the
 * snapshot does not carry host messages or banners: an empty field cannot be
 * distinguished from "no change".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsSheet(
    server: Server,
    onDismiss: () -> Unit,
    onGroupPermissions: () -> Unit,
    onApply: (
        name: String,
        welcomeMessage: String,
        maxClients: Int,
        password: String,
        hostMessage: String,
        hostBannerUrl: String,
        neededSecurityLevel: Int,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(server.name) }
    var welcome by remember { mutableStateOf(server.welcomeMessage) }
    var maxClients by remember { mutableStateOf(server.maxClients.toString()) }
    var password by remember { mutableStateOf("") }
    var hostMessage by remember { mutableStateOf("") }
    var hostBanner by remember { mutableStateOf("") }
    var securityLevel by remember { mutableStateOf("0") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 20.dp),
        ) {
            item { Text("服务器设置", style = MaterialTheme.typography.titleLarge) }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = welcome,
                    onValueChange = { welcome = it },
                    label = { Text("欢迎消息") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = maxClients,
                    onValueChange = { text ->
                        if (text.all { it.isDigit() } || text.isEmpty()) maxClients = text
                    },
                    label = { Text("人数上限") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("服务器密码（留空不改）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = hostMessage,
                    onValueChange = { hostMessage = it },
                    label = { Text("主机消息（留空不改）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = hostBanner,
                    onValueChange = { hostBanner = it },
                    label = { Text("主机横幅 URL（留空不改）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = securityLevel,
                    onValueChange = { text ->
                        if (text.all { it.isDigit() } || text.isEmpty()) securityLevel = text
                    },
                    label = { Text("身份安全等级") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onGroupPermissions) { Text("组权限") }
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = {
                        onApply(
                            name,
                            welcome,
                            maxClients.toIntOrNull() ?: server.maxClients,
                            password,
                            hostMessage,
                            hostBanner,
                            securityLevel.toIntOrNull() ?: 0,
                        )
                        onDismiss()
                    }) { Text("应用") }
                }
            }
        }
    }
}
