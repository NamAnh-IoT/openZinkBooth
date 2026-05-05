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

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photo.openzinkbooth.BuildConfig
import com.photo.openzinkbooth.R
import com.photo.openzinkbooth.core.utils.LogManager
import com.photo.openzinkbooth.ui.components.ScreenHeader
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// AboutScreen – app info, project links, debug tools (DEBUG builds only)
// ---------------------------------------------------------------------------

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    lastPrintBitmap: Bitmap? = null,
    debugDryRun: Boolean = false,
    onToggleDebugDryRun: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        ScreenHeader(title = stringResource(R.string.nav_about), onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // ── App identity ─────────────────────────────────────────────────
            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier         = Modifier
                        .size(128.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color(0xFF156080)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter            = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.app_name),
                        modifier           = Modifier.size(128.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text       = stringResource(R.string.app_name),
                    style      = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text  = stringResource(
                        R.string.about_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Project info ─────────────────────────────────────────────────
            AboutSectionTitle(stringResource(R.string.about_project_title))

            AboutListItem(
                icon       = Icons.Outlined.Person,
                headline   = "olie.xdeveloper",
                supporting = stringResource(R.string.about_maintainer_label)
            )
            AboutListItem(
                icon       = Icons.Outlined.Home,
                headline   = "openZinkBooth",
                supporting = stringResource(R.string.about_github_label),
                url        = "https://github.com/oliexdev/openZinkBooth",
                onOpenUrl  = { url ->
                    try { uriHandler.openUri(url) }
                    catch (e: Exception) { LogManager.e("AboutScreen", "Cannot open URL: $url", e) }
                }
            )
            AboutListItem(
                icon       = Icons.Outlined.Gavel,
                headline   = "GNU GPL v3.0",
                supporting = stringResource(R.string.about_license_label),
                url        = "https://www.gnu.org/licenses/gpl-3.0.html",
                onOpenUrl  = { url ->
                    try { uriHandler.openUri(url) }
                    catch (e: Exception) { LogManager.e("AboutScreen", "Cannot open URL: $url", e) }
                }
            )

            // ── Debug tools (DEBUG builds only) ──────────────────────────────
            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(modifier = Modifier.height(8.dp))

                AboutSectionTitle(
                    text  = stringResource(R.string.about_debug_title),
                    color = MaterialTheme.colorScheme.error
                )

                // Dry-run toggle – skips actual printing, stores bitmap for preview
                ListItem(
                    headlineContent   = { Text("Dry Run (kein Druck)") },
                    supportingContent = { Text("Druckt nicht, zeigt nur das Bitmap unten") },
                    leadingContent    = {
                        Icon(Icons.Outlined.Print, null,
                            tint = MaterialTheme.colorScheme.error)
                    },
                    trailingContent   = {
                        Switch(
                            checked         = debugDryRun,
                            onCheckedChange = { onToggleDebugDryRun() }
                        )
                    }
                )

                // Last sent print bitmap – tap to inspect
                lastPrintBitmap?.let { bmp ->
                    Text(
                        text     = "Last sent to printer: ${bmp.width}×${bmp.height}px",
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.foundation.Image(
                        bitmap             = bmp.asImageBitmap(),
                        contentDescription = "Last print bitmap",
                        contentScale       = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier           = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                            .border(
                                2.dp,
                                MaterialTheme.colorScheme.error,
                                androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                            )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                DebugTools()
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Debug tools section
// ---------------------------------------------------------------------------

@Composable
private fun DebugTools() {
    val logFile = LogManager.getLogFile()
    val logEntryCount by produceState(initialValue = 0) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            LogManager.getActualLogEntryCount()
        }
    }

    val scope   = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val success = LogManager.exportLogToUri(context, uri)
                if (success) LogManager.i("AboutScreen", "Log export successful")
                else         LogManager.e("AboutScreen", "Log export failed")
            }
        }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        ListItem(
            headlineContent   = { Text(stringResource(R.string.about_ble_log_lines, logEntryCount)) },
            supportingContent = {
                Text(
                    logFile?.name ?: stringResource(R.string.about_log_no_file),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            leadingContent = {
                Icon(Icons.Outlined.Terminal, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error)
            }
        )
        OutlinedButton(
            onClick  = { exportLauncher.launch("openzinkbooth_ble_log.txt") },
            enabled  = logFile != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            colors   = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Outlined.FileUpload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.about_export_log))
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable sub-components
// ---------------------------------------------------------------------------

@Composable
private fun AboutSectionTitle(
    text: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Text(
        text       = text,
        style      = MaterialTheme.typography.labelLarge,
        color      = color,
        fontWeight = FontWeight.Bold,
        modifier   = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun AboutListItem(
    icon: ImageVector,
    headline: String,
    supporting: String? = null,
    url: String? = null,
    onOpenUrl: ((String) -> Unit)? = null
) {
    ListItem(
        headlineContent   = { Text(headline, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent    = { Icon(icon, contentDescription = null) },
        trailingContent   = if (url != null) {
            {
                Icon(
                    Icons.AutoMirrored.Filled.Launch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        modifier = if (url != null && onOpenUrl != null)
            Modifier.clickable { onOpenUrl(url) }
        else
            Modifier,
        colors = ListItemDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent
        )
    )
}