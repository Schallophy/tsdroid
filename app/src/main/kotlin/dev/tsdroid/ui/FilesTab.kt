package dev.tsdroid.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tsdroid.SessionUiState
import dev.tsdroid.model.FileEntry
import dev.tsdroid.model.Transfer
import dev.tsdroid.model.TransferDirection
import dev.tsdroid.model.TransferState
import dev.tsdroid.ui.components.formatBytes
import dev.tsdroid.ui.components.formatStamp

/**
 * Files of the channel you are in: browse, download, upload.
 *
 * Transfers are shown in place rather than behind a modal, because a voice client
 * is often left open while something uploads and the progress is the only thing
 * worth watching.
 */
@Composable
fun FilesTab(
    ui: SessionUiState,
    onRefresh: () -> Unit,
    onDownload: (FileEntry) -> Unit,
    onUpload: (Uri) -> Unit,
) {
    val channelId = ui.filesChannelId ?: ui.state.ownChannelId
    val channelName = ui.state.channel(channelId)?.name ?: "当前频道"
    val files = channelId?.let { ui.state.filesIn(it) }.orEmpty()
    val hints = channelId?.let { ui.state.channel(it)?.permissionHints } ?: 0L
    // 0 means the core has not asked yet; only a stated denial hides an action.
    val canUpload = hints == 0L || (hints and 64L) != 0L
    val canDownload = hints == 0L || (hints and 128L) != 0L

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onUpload) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channelName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (files.isNotEmpty()) {
                    Text(
                        text = "${files.count { it.isFile }} 个文件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Fixed-height slot: the list does not move while a refresh runs.
        Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            if (ui.filesLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (files.isEmpty() && !ui.filesLoading) {
                EmptyState(
                    title = "这个频道还没有文件",
                    hint = when {
                        !ui.isOnline -> "连接后可上传"
                        !canUpload -> "没有上传权限"
                        else -> "点下方按钮上传"
                    },
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(files, key = { it.path + it.name }) { file ->
                        FileRow(file, canDownload, onDownload)
                    }
                }
            }
        }

        if (ui.transfers.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                Text(
                    text = "传输",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                ui.transfers.takeLast(3).forEach { transfer ->
                    TransferRow(transfer)
                }
            }
        }

        Button(
            onClick = { picker.launch("*/*") },
            enabled = ui.isOnline && channelId != null && canUpload,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("上传文件", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun FileRow(
    file: FileEntry,
    canDownload: Boolean,
    onDownload: (FileEntry) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = file.isFile && canDownload) { onDownload(file) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = if (file.isFile) "📄" else "📁",
            fontSize = 18.sp,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (file.isFile) {
                    "${formatBytes(file.size)} · ${formatStamp(file.dateTime * 1000)}"
                } else {
                    "文件夹"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (file.isFile) {
            IconButton(onClick = { onDownload(file) }) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "下载",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TransferRow(transfer: Transfer) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (transfer.direction == TransferDirection.Download) "↓" else "↑",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = transfer.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Text(
                text = when (transfer.state) {
                    TransferState.Running -> "${(transfer.fraction * 100).toInt()}%"
                    TransferState.Done -> "完成"
                    TransferState.Failed -> "失败"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (transfer.state) {
                    TransferState.Failed -> MaterialTheme.colorScheme.error
                    TransferState.Done -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        // Fixed-height slot: finishing a transfer does not resize its row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
                .height(3.dp),
        ) {
            if (transfer.state == TransferState.Running) {
                LinearProgressIndicator(
                    progress = { transfer.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}
