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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photo.openzinkbooth.R
import com.photo.openzinkbooth.core.ble.PrintStatus
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
// Printer status severity – drives pill colour in the READY state.
// NORMAL  = green, tappable (disconnect)
// WARNING = amber, informational only
// ERROR   = red,   informational only
// ---------------------------------------------------------------------------

private enum class StatusSeverity { NORMAL, WARNING, ERROR }

private data class StatusDisplay(
    val icon:     ImageVector,
    val labelRes: Int,
    val severity: StatusSeverity
)

// Maps every PrintStatus to a visual representation shown inside the
// connection pill when the printer is READY.
private fun statusDisplay(status: PrintStatus): StatusDisplay = when (status) {

    // ── Normal / ready to print ───────────────────────────────────────────
    PrintStatus.IDLE,
    PrintStatus.UNKNOWN          -> StatusDisplay(
        Icons.Outlined.Bluetooth, R.string.printer_connected, StatusSeverity.NORMAL
    )
    PrintStatus.PREPARING,
    PrintStatus.PAPER_PICK,
    PrintStatus.MULTI_PAGE_PICK  -> StatusDisplay(
        Icons.Outlined.HourglassEmpty, R.string.printer_connected, StatusSeverity.NORMAL
    )
    PrintStatus.PRINTING         -> StatusDisplay(
        Icons.Outlined.Print, R.string.printer_printing, StatusSeverity.NORMAL
    )

    // ── Warning – printable but needs attention ───────────────────────────
    PrintStatus.CALIBRATING      -> StatusDisplay(
        Icons.Outlined.Tune, R.string.printer_status_calibrating, StatusSeverity.WARNING
    )
    PrintStatus.TRAY_OPEN        -> StatusDisplay(
        Icons.Outlined.Inbox, R.string.printer_status_tray_open, StatusSeverity.WARNING
    )
    PrintStatus.NO_TRAY          -> StatusDisplay(
        Icons.Outlined.MoveToInbox, R.string.printer_status_no_tray, StatusSeverity.WARNING
    )
    PrintStatus.TRAY_MISALIGNED  -> StatusDisplay(
        Icons.Outlined.Inbox, R.string.printer_status_tray_misaligned, StatusSeverity.WARNING
    )

    // ── Error – not ready to print ────────────────────────────────────────
    PrintStatus.OUT_OF_PAPER,
    PrintStatus.OUT_OF_SUPPLIES,
    PrintStatus.NO_SUPPLIES_DETECTED -> StatusDisplay(
        Icons.Outlined.LayersClear, R.string.topbar_paper_empty, StatusSeverity.ERROR
    )
    PrintStatus.PAPER_JAM            -> StatusDisplay(
        Icons.Outlined.Block, R.string.printer_status_paper_jam, StatusSeverity.ERROR
    )
    PrintStatus.OVERHEATING          -> StatusDisplay(
        Icons.Outlined.DeviceThermostat, R.string.printer_status_overheating, StatusSeverity.ERROR
    )
    PrintStatus.FEED_PATH_OBSTRUCTED -> StatusDisplay(
        Icons.Outlined.Warning, R.string.printer_status_obstructed, StatusSeverity.ERROR
    )
    PrintStatus.BATTERY_CRITICAL     -> StatusDisplay(
        Icons.Outlined.BatteryAlert, R.string.printer_status_battery_critical, StatusSeverity.ERROR
    )
    PrintStatus.UNRECOVERABLE_ERROR  -> StatusDisplay(
        Icons.Outlined.ErrorOutline, R.string.printer_status_error, StatusSeverity.ERROR
    )
}

// ---------------------------------------------------------------------------
// Connection pill – animates between connection states
// ---------------------------------------------------------------------------

@Composable
fun ConnectionPill(
    state: ZinkUiState,
    onBluetoothRequest: () -> Unit,    // BLUETOOTH_DISABLED – open system BT dialog
    onOpenAppSettings: () -> Unit,     // BLUETOOTH_PERMISSION_DENIED – open app settings
    onDisconnect: () -> Unit,          // READY + NORMAL status – disconnect from printer
    onReconnect: () -> Unit,           // NOT_FOUND / ERROR / DISCONNECTED – retry scan
    modifier: Modifier = Modifier
) {
    // Animate whenever either the connection state or the print status changes
    AnimatedContent(
        targetState    = state.printerConnectionState to state.printStatus,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
        label          = "connection_pill",
        modifier       = modifier
    ) { (connectionState, printStatus) ->
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

            // ── Connected – colour driven by printer status ───────────────────
            PrinterConnectionState.READY -> {
                val display = statusDisplay(printStatus)
                val isNormal = display.severity == StatusSeverity.NORMAL
                val pillColor = when (display.severity) {
                    StatusSeverity.NORMAL  -> MaterialTheme.colorScheme.primaryContainer
                    StatusSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
                    StatusSeverity.ERROR   -> MaterialTheme.colorScheme.errorContainer
                }
                val iconTint = when (display.severity) {
                    StatusSeverity.NORMAL  -> MaterialTheme.colorScheme.onPrimaryContainer
                    StatusSeverity.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
                    StatusSeverity.ERROR   -> MaterialTheme.colorScheme.onErrorContainer
                }
                val textColor = when (display.severity) {
                    StatusSeverity.NORMAL  -> MaterialTheme.colorScheme.onPrimaryContainer
                    StatusSeverity.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
                    StatusSeverity.ERROR   -> MaterialTheme.colorScheme.onErrorContainer
                }
                // Show model name when idle and normal, otherwise show status label
                val label = if (isNormal && printStatus == PrintStatus.IDLE &&
                    state.printerModelName.isNotBlank())
                    state.printerModelName
                else
                    stringResource(display.labelRes)

                Surface(
                    onClick  = if (isNormal) onDisconnect else { {} },
                    enabled  = isNormal,
                    shape    = CircleShape,
                    color    = pillColor,
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector        = display.icon,
                            contentDescription = null,
                            modifier           = Modifier.size(14.dp),
                            tint               = iconTint
                        )
                        Text(
                            text     = label,
                            style    = MaterialTheme.typography.labelMedium,
                            color    = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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