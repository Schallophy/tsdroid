package dev.tsdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.tsdroid.SessionViewModel
import dev.tsdroid.ui.components.formatBytes

/**
 * Session settings, grouped by what they affect rather than by data type.
 *
 * The audio group explains its trade-off inline: the Bluetooth microphone option
 * forces the phone into call mode, which narrow-bands everything else it plays —
 * a setting people should turn on knowingly.
 */
@Composable
fun SettingsScreen(vm: SessionViewModel, onBack: () -> Unit) {
    val settings = remember { vm.settings() }
    var nickname by remember { mutableStateOf("") }
    var away by remember { mutableStateOf(false) }
    var awayMessage by remember { mutableStateOf(settings.awayMessage) }
    var gain by remember { mutableFloatStateOf(settings.micGain) }
    var output by remember { mutableFloatStateOf(settings.outputGain) }
    var noise by remember { mutableStateOf(settings.noiseSuppression) }
    var bluetooth by remember { mutableStateOf(settings.bluetoothMic) }
    var token by remember { mutableStateOf(settings.privilegeKey) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Settings is reached from the session screen, which is edge-to-edge;
            // without these the title sits under the status bar and the last row
            // under the gesture bar.
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 6.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }

        Section("身份") {
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("新昵称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.setNickname(nickname) },
                    enabled = nickname.isNotBlank(),
                ) { Text("改昵称") }
                Text(
                    text = "当前身份只在本机，导出功能还没做",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }

        Section("服务器权限") {
            Text(
                text = "首次连接兑换权限密钥（服务器启动日志里的 token=…）。它只对这一次生效，" +
                    "把密钥对应的分组授予当前 UID；之后权限就绑在这个身份上。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    settings.privilegeKey = it.trim()
                },
                label = { Text("权限密钥") },
                placeholder = { Text("留空表示不兑换") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "当前身份所在分组：" +
                    (vm.ui.value.state.ownServerGroups.joinToString("、") { it.name }
                        .ifEmpty { "未连接或没有" }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("状态") {
            ToggleRow(
                label = "离开",
                hint = "别人会看到你处于离开状态",
                checked = away,
                onChange = {
                    away = it
                    vm.setAway(it, awayMessage)
                },
            )
            if (away) {
                OutlinedTextField(
                    value = awayMessage,
                    onValueChange = { awayMessage = it },
                    label = { Text("离开说明") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.setAway(true, awayMessage) }) { Text("更新说明") }
            }
        }

        Section("音频") {
            ToggleRow(
                label = "麦克风降噪",
                hint = "系统级噪声抑制，开会时更干净",
                checked = noise,
                onChange = {
                    noise = it
                    vm.applyAudioSettings(gain, it, bluetooth)
                },
            )
            ToggleRow(
                label = "使用蓝牙耳机麦克风",
                hint = "必须走通话通道：开启期间手机其他声音会变窄带，音质下降。没连耳机时自动用手机麦克风。",
                checked = bluetooth,
                onChange = {
                    bluetooth = it
                    vm.applyAudioSettings(gain, noise, it)
                },
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "麦克风增益 ${"%.1f".format(gain)}×",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = gain,
                    onValueChange = { gain = it },
                    onValueChangeFinished = { vm.applyAudioSettings(gain, noise, bluetooth) },
                    valueRange = 0.2f..4f,
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "扬声器音量 ${"%.1f".format(output)}×",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "拉高会整体放大别人说话的声音；超过 1.0× 时软限幅，不会破音。" +
                        "播放走媒体音量，请同时用音量键确认媒体音量没有调低。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = output,
                    onValueChange = { output = it },
                    onValueChangeFinished = { vm.applyOutputGain(output) },
                    valueRange = 0.5f..2f,
                )
            }
        }

        Section("安全") {
            // Clearing lives here rather than on the connect screen: it is a
            // once-in-a-while action, and putting it next to the password field
            // invites mis-taps that force a retype.
            val store = remember { vm.serverStore() }
            var hasSaved by remember { mutableStateOf(store.hasPasswords()) }
            var confirmClear by remember { mutableStateOf(false) }

            Text(
                text = "服务器密码用 Android Keystore 持有的密钥加密后存放在本机。换机、抹机或从备份恢复后密文解不开，会当作没保存过，需要重新输入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { confirmClear = true },
                enabled = hasSaved,
            ) {
                Text(if (hasSaved) "清除已保存的密码" else "没有保存的密码")
            }
            if (confirmClear) {
                AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text("清除已保存的密码？") },
                    text = { Text("下次连接这些服务器要重新输入密码。服务器地址和昵称不受影响。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                store.clearPasswords()
                                hasSaved = false
                                confirmClear = false
                            },
                        ) { Text("清除") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClear = false }) { Text("取消") }
                    },
                )
            }
        }

        Section("关于") {
            Text(
                text = "协议核心：Rust + tsclientlib（钉在固定提交）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "语音：Opus 24 kbps 单声道，采集与播放由 Android 音频栈负责",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // tsclientlib is Apache-2.0 OR MIT and is compiled into the
                // shipped .so, so the notice has to travel with the APK. The
                // files live in assets/licenses and are copied from tsbridge,
                // whose NOTICE is the single source of truth.
                text = "本应用包含 tsclientlib（Apache-2.0 OR MIT）与 Opus（BSD）等第三方组件，" +
                    "完整许可见安装包 assets/licenses/NOTICE",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                // surfaceContainer sits one step above the page in both palettes,
                // so a group reads as a card instead of blending into the page.
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(14.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
