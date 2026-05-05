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

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.photo.openzinkbooth.R
import com.photo.openzinkbooth.ui.components.ScreenHeader
import com.photo.openzinkbooth.ui.viewmodel.ManualScanDevice
import com.photo.openzinkbooth.ui.viewmodel.PrinterConnectionState
import com.photo.openzinkbooth.ui.viewmodel.ZinkUiState

// ---------------------------------------------------------------------------
// PrinterScreen – BLE connection management.
//
// Simplified layout:
//   - Status card: model name + connection state (+ spinner when scanning)
//   - Device list when manual scan is running
//   - "Search for printer" button – always visible
//
// Disconnect and battery/paper detail have moved to PrinterConfigScreen.
// ---------------------------------------------------------------------------

@Composable
fun PrinterScreen(
    state: ZinkUiState,
    manualScanDevices: List<ManualScanDevice>,
    onBack: () -> Unit,
    onStartManualScan: () -> Unit,
    onConnectDevice: (ManualScanDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        ScreenHeader(
            title  = stringResource(R.string.settings_group_printer),
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ── Found devices list (manual scan results) ───────────────────────
            if (manualScanDevices.isNotEmpty()) {
                Text(
                    text  = stringResource(R.string.printer_found_devices),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                manualScanDevices.forEach { device ->
                    OutlinedCard(
                        onClick  = { onConnectDevice(device) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector        = Icons.Outlined.Print,
                                contentDescription = null,
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text  = device.model.displayName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text  = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector        = Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── Search button – always visible ────────────────────────────────
            Button(
                onClick  = onStartManualScan,
                enabled  = !state.printerConnecting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector        = Icons.Outlined.BluetoothSearching,
                    contentDescription = null,
                    modifier           = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (state.printerScanning)
                        stringResource(R.string.printer_scanning)
                    else
                        stringResource(R.string.printer_search)
                )
                if (state.printerScanning) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier    = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color       = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}