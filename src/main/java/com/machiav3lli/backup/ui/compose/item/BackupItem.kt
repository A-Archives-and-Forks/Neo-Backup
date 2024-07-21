package com.machiav3lli.backup.ui.compose.item

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.machiav3lli.backup.BACKUP_DATE_TIME_SHOW_FORMATTER
import com.machiav3lli.backup.R
import com.machiav3lli.backup.dbs.entity.Backup
import com.machiav3lli.backup.handler.ShellCommands.Companion.currentProfile
import com.machiav3lli.backup.ui.compose.icons.Phosphor
import com.machiav3lli.backup.ui.compose.icons.phosphor.ClockCounterClockwise
import com.machiav3lli.backup.ui.compose.icons.phosphor.Lock
import com.machiav3lli.backup.ui.compose.icons.phosphor.LockOpen
import com.machiav3lli.backup.ui.compose.icons.phosphor.TrashSimple

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BackupItem(
    item: Backup,
    onRestore: (Backup) -> Unit = { },
    onDelete: (Backup) -> Unit = { },
    onNote: (Backup) -> Unit = { },
    rewriteBackup: (Backup, Backup) -> Unit = { backup, changedBackup -> },
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        headlineContent = {
            Row(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.versionName ?: "",
                        modifier = Modifier
                            .align(Alignment.CenterVertically),
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 2,
                        style = MaterialTheme.typography.titleMedium
                    )
                    AnimatedVisibility(visible = (item.cpuArch != android.os.Build.SUPPORTED_ABIS[0])) {
                        Text(
                            text = " ${item.cpuArch}",
                            color = Color.Red,
                            modifier = Modifier
                                .align(Alignment.CenterVertically),
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Row(
                    modifier = Modifier.wrapContentWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NoteTagItem(
                        tag = item.note.ifEmpty { stringResource(id = R.string.edit_note) },
                        action = item.note.isEmpty(),
                        onClick = { onNote(item) },
                    )
                    BackupLabels(item = item)
                }
            }
        },
        supportingContent = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = true)
                    ) {
                        Text(
                            text = item.backupDate.format(BACKUP_DATE_TIME_SHOW_FORMATTER),
                            modifier = Modifier.align(Alignment.Top),
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (item.tag.isNotEmpty())
                            Text(
                                text = " ${item.tag}",
                                modifier = Modifier.align(Alignment.Top),
                                softWrap = true,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 3,
                                style = MaterialTheme.typography.labelMedium,
                            )
                    }
                    Row {
                        Text(
                            text = if (item.backupVersionCode == 0) "old" else "${item.backupVersionCode / 1000}.${item.backupVersionCode % 1000}",
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        AnimatedVisibility(visible = item.isEncrypted) {
                            val description = "${item.cipherType}"
                            val showTooltip = remember { mutableStateOf(false) }
                            if (showTooltip.value) {
                                Tooltip(description, showTooltip)
                            }
                            Text(
                                text = " enc",
                                color = Color.Red,
                                modifier = Modifier
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { showTooltip.value = true }
                                    ),
                                softWrap = true,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        val compressionText = if (item.isCompressed) {
                            if (item.compressionType.isNullOrEmpty())
                                " gz"
                            else
                                " ${item.compressionType}"
                        } else ""
                        val fileSizeText = if (item.backupVersionCode != 0)
                            " - ${Formatter.formatFileSize(LocalContext.current, item.size)}"
                        else ""
                        Text(
                            text = compressionText + fileSizeText,
                            modifier = Modifier.align(Alignment.Top),
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        AnimatedVisibility(visible = (item.profileId != currentProfile)) {
                            Row {
                                Text(
                                    text = " 👤",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    text = "${item.profileId}",
                                    color = Color.Red,
                                    softWrap = true,
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    var persistent by remember(item.persistent) {
                        mutableStateOf(item.persistent)
                    }
                    val togglePersistent = {
                        persistent = !persistent
                        rewriteBackup(item, item.copy(persistent = persistent))
                    }

                    if (persistent)
                        RoundButton(
                            icon = Phosphor.Lock,
                            tint = Color.Red,
                            onClick = togglePersistent
                        )
                    else
                        RoundButton(icon = Phosphor.LockOpen, onClick = togglePersistent)

                    Spacer(modifier = Modifier.weight(1f))
                    ElevatedActionButton(
                        icon = Phosphor.TrashSimple,
                        text = stringResource(id = R.string.deleteBackup),
                        positive = false,
                        withText = false,
                        onClick = { onDelete(item) },
                    )
                    ElevatedActionButton(
                        icon = Phosphor.ClockCounterClockwise,
                        text = stringResource(id = R.string.restore),
                        positive = true,
                        onClick = { onRestore(item) },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RestoreBackupItem(
    item: Backup,
    index: Int,
    isApkChecked: Boolean = false,
    isDataChecked: Boolean = false,
    onApkClick: (String, Boolean, Int) -> Unit = { _: String, _: Boolean, _: Int -> },
    onDataClick: (String, Boolean, Int) -> Unit = { _: String, _: Boolean, _: Int -> },
) {
    var apkChecked by remember(isApkChecked) { mutableStateOf(isApkChecked) }
    var dataChecked by remember(isDataChecked) { mutableStateOf(isDataChecked) }
    val showApk by remember(item) { mutableStateOf(item.hasApk) }
    val showData by remember(item) { mutableStateOf(item.hasData) }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        leadingContent = {
            Row {
                Checkbox(checked = apkChecked,
                    enabled = showApk,
                    onCheckedChange = {
                        apkChecked = it
                        onApkClick(item.packageName, it, index)
                    }
                )
                Checkbox(checked = dataChecked,
                    enabled = showData,
                    onCheckedChange = {
                        dataChecked = it
                        onDataClick(item.packageName, it, index)
                    }
                )
            }

        },
        headlineContent = {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = item.versionName ?: "",
                    modifier = Modifier
                        .align(Alignment.CenterVertically),
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "(${item.cpuArch})",
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .weight(1f),
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium,
                )
                BackupLabels(item = item)
            }
        },
        supportingContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = true)
                ) {
                    Text(
                        text = item.backupDate.format(BACKUP_DATE_TIME_SHOW_FORMATTER),
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (item.tag.isNotEmpty())
                        Text(
                            text = " - ${item.tag}",
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                }
                Row {
                    Text(
                        text = if (item.backupVersionCode == 0) "old" else "${item.backupVersionCode / 1000}.${item.backupVersionCode % 1000}",
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    AnimatedVisibility(visible = item.isEncrypted) {
                        val description = "${item.cipherType}"
                        val showTooltip = remember { mutableStateOf(false) }
                        if (showTooltip.value) {
                            Tooltip(description, showTooltip)
                        }
                        Text(
                            text = " - enc",
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { showTooltip.value = true }
                                ),
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    val compressionText = if (item.isCompressed)
                        " - ${item.compressionType?.replace("/", " ")}"
                    else ""
                    val fileSizeText = if (item.backupVersionCode != 0)
                        " - ${Formatter.formatFileSize(LocalContext.current, item.size)}"
                    else ""
                    Text(
                        text = compressionText + fileSizeText,
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    )
}