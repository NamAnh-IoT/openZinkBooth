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
package com.photo.openzinkbooth.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photo.openzinkbooth.R
import com.photo.openzinkbooth.ui.viewmodel.PrinterConnectionState
import com.photo.openzinkbooth.ui.viewmodel.ZinkUiState

// ---------------------------------------------------------------------------
// TopBar – two rows shown on the Camera screen.
// Row 1: hamburger │ app name │ [symmetry spacer]
// Row 2: ConnectionPill (left) ─ spacer ─ PaperStatusPill (right)
// ---------------------------------------------------------------------------

@Composable
fun TopBarMain(
    onHamburgerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HamburgerButton(onClick = onHamburgerClick)

        Text(
            text      = stringResource(R.string.app_name),
            style     = MaterialTheme.typography.titleMedium,
            color     = MaterialTheme.colorScheme.onBackground,
            modifier  = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.size(40.dp))
    }
}

@Composable
fun TopBarStatus(
    state: ZinkUiState,
    onPaperPillClick: () -> Unit,
    onPrinterPillClick: () -> Unit,   // BLUETOOTH_DISABLED → request BT enable
    onOpenAppSettings: () -> Unit,    // BLUETOOTH_PERMISSION_DENIED → open app settings
    onPrinterDisconnect: () -> Unit,  // READY → disconnect
    onPrinterReconnect: () -> Unit,   // NOT_FOUND / ERROR / DISCONNECTED → retry scan
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Left: animated connection pill
        ConnectionPill(
            state               = state,
            onBluetoothRequest  = onPrinterPillClick,
            onOpenAppSettings   = onOpenAppSettings,
            onDisconnect        = onPrinterDisconnect,
            onReconnect         = onPrinterReconnect
        )

        Spacer(modifier = Modifier.weight(1f))

        // Flash indicator – shown whenever flash is enabled, regardless of connection
        if (state.flashEnabled) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector        = Icons.Outlined.FlashOn,
                        contentDescription = null,
                        modifier           = Modifier.size(16.dp),
                        tint               = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Battery (only when connected)
        if (state.printerConnected) {
            BatteryChip(batteryLevel = state.batteryLevel)
            Spacer(modifier = Modifier.width(6.dp))
        }

        PaperStatusPill(
            isEmpty   = state.paperEmpty,
            count     = state.paperCount,
            showCount = state.showPaperCount,
            onClick   = onPaperPillClick
        )
    }
}

// ---------------------------------------------------------------------------
// Connection pill – animates between connection states
// ---------------------------------------------------------------------------

@Composable
fun ConnectionPill(
    state: ZinkUiState,
    onBluetoothRequest: () -> Unit,    // BLUETOOTH_DISABLED – open system BT dialog
    onOpenAppSettings: () -> Unit,     // BLUETOOTH_PERMISSION_DENIED – open app settings
    onDisconnect: () -> Unit,          // READY – disconnect from printer
    onReconnect: () -> Unit,           // NOT_FOUND / ERROR / DISCONNECTED – retry scan
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState   = state.printerConnectionState,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
        label         = "connection_pill",
        modifier      = modifier
    ) { connectionState ->
        when (connectionState) {

            // ── Busy states – not tappable ────────────────────────────────────
            PrinterConnectionState.SCANNING,
            PrinterConnectionState.CONNECTING -> {
                Surface(
                    shape    = CircleShape,
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color       = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text  = if (connectionState == PrinterConnectionState.SCANNING)
                                stringResource(R.string.printer_scanning)
                            else
                                stringResource(R.string.printer_connecting),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Connected – tap to disconnect ─────────────────────────────────
            PrinterConnectionState.READY -> {
                Surface(
                    onClick  = onDisconnect,
                    shape    = CircleShape,
                    color    = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text  = state.printerModelName.ifBlank {
                                stringResource(R.string.printer_connected)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // ── Not found / disconnected – tap to retry scan ──────────────────
            PrinterConnectionState.NOT_FOUND,
            PrinterConnectionState.DISCONNECTED -> {
                Surface(
                    onClick  = onReconnect,
                    shape    = CircleShape,
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BluetoothDisabled,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text  = if (connectionState == PrinterConnectionState.NOT_FOUND)
                                stringResource(R.string.printer_not_found)
                            else
                                stringResource(R.string.printer_not_connected),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Error – tap to retry scan ─────────────────────────────────────
            PrinterConnectionState.ERROR -> {
                Surface(
                    onClick  = onReconnect,
                    shape    = CircleShape,
                    color    = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text  = stringResource(R.string.printer_error_generic),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // ── Bluetooth disabled – tap to open BT enable dialog ─────────────
            PrinterConnectionState.BLUETOOTH_DISABLED -> {
                Surface(
                    onClick  = onBluetoothRequest,
                    shape    = CircleShape,
                    color    = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BluetoothDisabled,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text  = stringResource(R.string.bluetooth_disabled),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // ── Permission denied – tap to open app settings ──────────────────
            PrinterConnectionState.BLUETOOTH_PERMISSION_DENIED -> {
                Surface(
                    onClick  = onOpenAppSettings,
                    shape    = CircleShape,
                    color    = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BluetoothDisabled,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text  = stringResource(R.string.bluetooth_permission_denied),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// StatusPill – no longer used in ConnectionPill but kept for potential reuse
// ---------------------------------------------------------------------------

@Composable
fun StatusPill(
    connected: Boolean,
    modelName: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape    = CircleShape,
        color    = if (connected)
            MaterialTheme.colorScheme.secondaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (connected) Icons.Outlined.CheckCircle
                else Icons.Outlined.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (connected)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text  = if (connected) modelName
                else stringResource(R.string.printer_not_connected),
                style = MaterialTheme.typography.labelMedium,
                color = if (connected)
                    MaterialTheme.colorScheme.onSecondaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Battery chip – shown next to the paper pill when connected
// ---------------------------------------------------------------------------

@Composable
private fun BatteryChip(
    batteryLevel: Int?,
    modifier: Modifier = Modifier
) {
    if (batteryLevel == null) return

    val isLow     = batteryLevel <= 20
    val chipColor = if (isLow)
        MaterialTheme.colorScheme.errorContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val textColor = if (isLow)
        MaterialTheme.colorScheme.error
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    val icon = when {
        batteryLevel <= 20  -> Icons.Outlined.Battery1Bar
        batteryLevel <= 50  -> Icons.Outlined.Battery3Bar
        batteryLevel <= 80  -> Icons.Outlined.Battery5Bar
        else                -> Icons.Outlined.Battery6Bar
    }

    Surface(
        shape    = CircleShape,
        color    = chipColor,
        modifier = modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                modifier           = Modifier.size(16.dp),
                tint               = textColor
            )
            Text(
                text  = "$batteryLevel%",
                style = MaterialTheme.typography.labelMedium,
                color = textColor
            )
        }
    }
}

// ---------------------------------------------------------------------------
// PaperStatusPill
// ---------------------------------------------------------------------------

@Composable
fun PaperStatusPill(
    isEmpty: Boolean,
    count: Int,        // -1 = never refilled
    showCount: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chipColor = if (isEmpty)
        MaterialTheme.colorScheme.errorContainer
    else
        MaterialTheme.colorScheme.secondaryContainer

    val iconTint = if (isEmpty)
        MaterialTheme.colorScheme.error
    else
        MaterialTheme.colorScheme.onSecondaryContainer

    val textColor = if (isEmpty)
        MaterialTheme.colorScheme.onErrorContainer
    else
        MaterialTheme.colorScheme.onSecondaryContainer

    val label = when {
        isEmpty            -> stringResource(R.string.topbar_paper_empty)
        showCount          -> stringResource(R.string.topbar_paper_count, count)
        else               -> null   // connected but never refilled → icon only
    }

    Surface(
        onClick  = onClick,
        shape    = CircleShape,
        color    = chipColor,
        modifier = modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isEmpty) Icons.Outlined.Warning
                else Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint     = iconTint
            )
            if (label != null) {
                Text(
                    text  = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = textColor
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hamburger button
// ---------------------------------------------------------------------------

@Composable
private fun HamburgerButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector        = Icons.Outlined.Menu,
            contentDescription = stringResource(R.string.nav_open_description),
            tint               = MaterialTheme.colorScheme.onSurface
        )
    }
}