/*
 * openZinkBooth
 * Copyright (C) 2026 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.photo.openzinkbooth.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.photo.openzinkbooth.R
import com.photo.openzinkbooth.ui.viewmodel.RemoteShutterKey
import com.photo.openzinkbooth.ui.viewmodel.ZinkUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ZinkUiState,
    onBack: () -> Unit,
    onNavigateToPrinter: () -> Unit,
    onNavigateToPrinterConfig: () -> Unit,
    onNavigateToFrameManager: () -> Unit,
    onToggleFrontCamera: (Boolean) -> Unit,
    onToggleFlash: (Boolean) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onToggleShutterSound: (Boolean) -> Unit,
    onStorageUriSelected: (Uri?) -> Unit,
    onToggleRemoteShutter: (Boolean) -> Unit,
    onSetRemoteShutterKey: (RemoteShutterKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist read+write permission across app restarts; without this
            // the ContentResolver loses access to the folder after the process dies.
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        onStorageUriSelected(uri)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.camera_back_description))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_printer)) {
                PrinterSettingsRow(
                    printerName        = state.printerModelName.ifBlank {
                        stringResource(R.string.printer_no_printer_selected)
                    },
                    connected          = state.printerConnected,
                    onNavigateToPrinter      = onNavigateToPrinter,
                    onNavigateToPrinterConfig = onNavigateToPrinterConfig
                )
            }

            SettingsGroup(title = stringResource(R.string.settings_group_camera)) {
                SettingsToggleRow(
                    icon     = Icons.Outlined.CameraFront,
                    label    = stringResource(R.string.settings_front_camera),
                    checked  = state.useFrontCamera,
                    onToggle = onToggleFrontCamera
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsToggleRow(
                    icon     = Icons.Outlined.FlashOn,
                    label    = stringResource(R.string.settings_flash),
                    checked  = state.flashEnabled,
                    onToggle = onToggleFlash
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsToggleRow(
                    icon     = Icons.Outlined.SettingsRemote,
                    label    = stringResource(R.string.settings_remote_shutter_enabled),
                    checked  = state.remoteShutterEnabled,
                    onToggle = onToggleRemoteShutter
                )
                if (state.remoteShutterEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    RemoteShutterKeyRow(
                        selected = state.remoteShutterKey,
                        onSelect = onSetRemoteShutterKey
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.settings_group_appearance)) {
                SettingsToggleRow(
                    icon     = Icons.Outlined.Palette,
                    label    = stringResource(R.string.settings_dynamic_color),
                    checked  = state.dynamicColor,
                    onToggle = onToggleDynamicColor
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsActionRow(
                    icon    = Icons.Outlined.PhotoFilter,
                    label   = stringResource(R.string.settings_frames),
                    onClick = onNavigateToFrameManager
                )
            }

            SettingsGroup(title = stringResource(R.string.settings_group_app)) {
                SettingsToggleRow(
                    icon     = Icons.Outlined.VolumeUp,
                    label    = stringResource(R.string.settings_shutter_sound),
                    checked  = state.shutterSoundEnabled,
                    onToggle = onToggleShutterSound
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                StorageLocationRow(
                    storageUri = state.storageUri,
                    onPick     = { folderPicker.launch(state.storageUri) },
                    onClear    = { onStorageUriSelected(null) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text     = title,
            style    = MaterialTheme.typography.labelMedium,
            color    = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            shape  = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    label: String,
    sublabel: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsIcon(icon, enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            if (sublabel != null) {
                Text(
                    text  = sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .let { if (enabled) it else it.copy(alpha = 0.38f) }
                )
            }
        }
        Switch(
            checked         = checked,
            onCheckedChange = onToggle,
            enabled         = enabled
        )
    }
}

@Composable
private fun SettingsInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showDot: Boolean = false,
    dotColor: Color = MaterialTheme.colorScheme.tertiary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsIcon(icon)
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier.size(7.dp).clip(CircleShape)
                        .background(dotColor)
                )
            }
            Text(
                text  = value,
                style = MaterialTheme.typography.labelMedium,
                color = valueColor
            )
        }
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    label: String,
    sublabel: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint
            )
            if (sublabel != null) {
                Text(
                    text  = sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector        = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier           = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingsIcon(
    icon: ImageVector,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Icon(
        imageVector        = icon,
        contentDescription = null,
        tint               = if (enabled) tint else tint.copy(alpha = 0.38f),
        modifier           = Modifier.size(24.dp)
    )
}

// ---------------------------------------------------------------------------
// Storage location row – shows the configured path with an X to clear it,
// or a placeholder when no path is set.
// ---------------------------------------------------------------------------

@Composable
private fun StorageLocationRow(
    storageUri: android.net.Uri?,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsIcon(Icons.Outlined.FolderOpen)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = stringResource(R.string.settings_storage_location),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text  = storageUri
                    ?.lastPathSegment?.substringAfterLast(':')
                    ?: stringResource(R.string.settings_storage_none),
                style = MaterialTheme.typography.bodySmall,
                color = if (storageUri != null)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (storageUri != null) {
            // X icon to clear the storage location
            IconButton(onClick = onClear) {
                Icon(
                    imageVector        = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.settings_storage_clear),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(18.dp)
                )
            }
        } else {
            Icon(
                imageVector        = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier           = Modifier.size(18.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Printer row – left area opens PrinterScreen, gear icon opens PrinterConfigScreen.
// Only shows the gear when connected (config only available via RFCOMM).
// ---------------------------------------------------------------------------

@Composable
private fun PrinterSettingsRow(
    printerName: String,
    connected: Boolean,
    onNavigateToPrinter: () -> Unit,
    onNavigateToPrinterConfig: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onNavigateToPrinter)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsIcon(
            icon = Icons.Outlined.Print,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = printerName,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text  = if (connected)
                    stringResource(R.string.settings_printer_connected)
                else
                    stringResource(R.string.settings_printer_disconnected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Gear icon – only visible when connected (RFCOMM required for config)
        if (connected) {
            IconButton(onClick = onNavigateToPrinterConfig) {
                Icon(
                    imageVector        = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.printer_config_title),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(22.dp)
                )
            }
        } else {
            Icon(
                imageVector        = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier           = Modifier.size(18.dp)
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteShutterKeyRow(
    selected: RemoteShutterKey,
    onSelect: (RemoteShutterKey) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val keys = RemoteShutterKey.entries

    ListItem(
        leadingContent = {
            Icon(
                Icons.Outlined.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        headlineContent = {
            Text(
                stringResource(R.string.settings_remote_key_label),
                style = MaterialTheme.typography.bodyLarge
            )
        },
        trailingContent = {
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(
                        text  = stringResource(selected.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint     = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(
                    expanded         = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    keys.forEach { key ->
                        DropdownMenuItem(
                            text         = { Text(stringResource(key.labelRes)) },
                            onClick      = { onSelect(key); expanded = false },
                            trailingIcon = if (key == selected) {
                                { Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) }
                            } else null
                        )
                    }
                }
            }
        }
    )
}