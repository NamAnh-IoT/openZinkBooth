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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.photo.openzinkbooth.R
import com.photo.openzinkbooth.core.ble.*
import com.photo.openzinkbooth.ui.components.ScreenHeader
import com.photo.openzinkbooth.ui.viewmodel.ZinkUiState
import java.text.SimpleDateFormat

// ---------------------------------------------------------------------------
// PrinterConfigScreen – reads and writes printer configuration via RFCOMM.
//
// Layout: M3 ListItem rows throughout.
// - Left: icon + label (headlineContent)
// - Right: current value or dropdown (trailingContent)
// Section headers use M3 labelLarge in primary color.
// ---------------------------------------------------------------------------

@Composable
fun PrinterConfigScreen(
    identity: PrinterIdentity?,
    config: PrinterConfig?,
    uiState: ZinkUiState,
    onBack: () -> Unit,
    onApplyConfig: (PrinterConfig) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        ScreenHeader(
            title  = stringResource(R.string.printer_config_title),
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Status ────────────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.printer_config_status))

            ConfigInfoRow(
                icon  = Icons.Outlined.Print,
                label = stringResource(R.string.printer_config_model),
                value = uiState.printerModelName.ifBlank {
                    stringResource(R.string.printer_no_printer_selected)
                }
            )

            ConfigInfoRow(
                icon  = Icons.Outlined.Circle,
                label = stringResource(R.string.printer_config_connection),
                value = uiState.printerConnectionState.name
                    .replace("_", " ").lowercase()
                    .replaceFirstChar { it.uppercase() }
            )

            uiState.batteryLevel?.let { level ->
                ConfigInfoRow(
                    icon  = Icons.Outlined.BatteryFull,
                    label = stringResource(R.string.printer_battery),
                    value = "$level %"
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Device info ───────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.printer_config_identity))

            if (identity == null) {
                ListItem(
                    headlineContent = {
                        Text(
                            text  = stringResource(R.string.printer_config_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            } else {
                identity.softwareVersion?.let {
                    ConfigInfoRow(Icons.Outlined.Memory, stringResource(R.string.printer_config_firmware), it)
                }
                identity.hardwareVersion?.let {
                    ConfigInfoRow(Icons.Outlined.Hardware, stringResource(R.string.printer_config_hardware), it)
                }
                identity.serialNumber?.let {
                    ConfigInfoRow(Icons.Outlined.Numbers, stringResource(R.string.printer_config_serial), it)
                }
                identity.bluetoothMacAddress?.let {
                    ConfigInfoRow(Icons.Outlined.Bluetooth, stringResource(R.string.printer_config_mac), it)
                }
                identity.customName?.let {
                    ConfigInfoRow(Icons.Outlined.Label, stringResource(R.string.printer_config_device_name), it)
                }
                identity.firstPrintDate?.let { date ->
                    val fmt = SimpleDateFormat("dd.MM.yyyy", LocalLocale.current.platformLocale)
                    ConfigInfoRow(Icons.Outlined.CalendarToday, stringResource(R.string.printer_config_first_print), fmt.format(date))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Configuration ─────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.printer_config_settings))

            if (config == null) {
                ListItem(
                    headlineContent = {
                        Text(
                            text  = stringResource(R.string.printer_config_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            } else {
                ConfigDropdownRow(
                    icon     = Icons.Outlined.Bedtime,
                    label    = stringResource(R.string.printer_config_sleep),
                    selected = stringResource(config.sleepTimer?.labelRes ?: R.string.not_available),
                    options  = SleepTimer.entries.map { stringResource(it.labelRes) },
                    onSelect = { index ->
                        onApplyConfig(PrinterConfig(sleepTimer = SleepTimer.entries[index]))
                    }
                )

                ConfigDropdownRow(
                    icon     = Icons.Outlined.PowerSettingsNew,
                    label    = stringResource(R.string.printer_config_autooff),
                    selected = stringResource(config.autoOff?.labelRes ?: R.string.not_available),
                    options  = AutoOff.entries.map { stringResource(it.labelRes) },
                    onSelect = { index ->
                        onApplyConfig(PrinterConfig(autoOff = AutoOff.entries[index]))
                    }
                )

                ConfigDropdownRow(
                    icon     = Icons.Outlined.Palette,
                    label    = stringResource(R.string.printer_config_led_color),
                    selected = stringResource(config.userColor?.labelRes ?: R.string.not_available),
                    options  = UserColor.entries.map { stringResource(it.labelRes) },
                    onSelect = { index ->
                        onApplyConfig(PrinterConfig(userColor = UserColor.entries[index]))
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Disconnect – only visible when connected
            if (uiState.printerConnected) {
                OutlinedButton(
                    onClick  = onDisconnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Outlined.BluetoothDisabled,
                        contentDescription = null,
                        modifier           = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.printer_disconnect))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Private composables
// ---------------------------------------------------------------------------

/** M3-conformant section label in primary labelLarge style. */
@Composable
private fun SectionLabel(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

/** Read-only info row: icon + label left, value right. */
@Composable
private fun ConfigInfoRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        headlineContent = {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        trailingContent = {
            Text(
                text  = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

/**
 * Editable dropdown row: icon + label left, dropdown button right.
 * onSelect receives the index of the selected option.
 */
@Composable
private fun ConfigDropdownRow(
    icon: ImageVector,
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        leadingContent = {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        headlineContent = {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        trailingContent = {
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(
                        text  = selected,
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
                    options.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text         = { Text(option) },
                            onClick      = { expanded = false; onSelect(index) },
                            trailingIcon = if (option == selected) {
                                { Icon(Icons.Outlined.Check, null,
                                    tint = MaterialTheme.colorScheme.primary) }
                            } else null
                        )
                    }
                }
            }
        }
    )
}