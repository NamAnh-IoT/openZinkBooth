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

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photo.openzinkbooth.R
import com.photo.openzinkbooth.ui.viewmodel.Screen
import com.photo.openzinkbooth.ui.viewmodel.ZinkUiState

@Composable
fun NavDrawer(
    open: Boolean,
    state: ZinkUiState,
    onClose: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onOpenPhoto: () -> Unit
) {
    AnimatedVisibility(
        visible = open,
        enter   = fadeIn(tween(300)),
        exit    = fadeOut(tween(300))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .clickable(onClick = onClose)
            )

            AnimatedVisibility(
                visible = open,
                enter = slideInHorizontally(tween(300)) { -it },
                exit  = slideOutHorizontally(tween(250)) { -it }
            ) {
                DrawerPanel(
                    currentScreen = state.screen,
                    state         = state,
                    onNavigate    = onNavigate,
                    onOpenPhoto   = onOpenPhoto
                )
            }
        }
    }
}

@Composable
private fun DrawerPanel(
    currentScreen: Screen,
    state: ZinkUiState,
    onNavigate: (Screen) -> Unit,
    onOpenPhoto: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(260.dp)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(topEnd = 0.dp, bottomEnd = 0.dp)
            )
    ) {
        DrawerHeader()

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(8.dp))

        // Main navigation
        DrawerItem(
            icon    = Icons.Outlined.CameraAlt,
            label   = stringResource(R.string.nav_camera),
            active  = currentScreen == Screen.CAMERA,
            onClick = { onNavigate(Screen.CAMERA) }
        )
        // Open Photo – launches SAF picker, not a screen navigation
        DrawerItem(
            icon    = Icons.Outlined.FolderOpen,
            label   = stringResource(R.string.nav_open_photo),
            active  = false,
            onClick = onOpenPhoto
        )
        DrawerItem(
            icon    = Icons.Outlined.Settings,
            label   = stringResource(R.string.nav_settings),
            active  = currentScreen == Screen.SETTINGS,
            onClick = { onNavigate(Screen.SETTINGS) }
        )

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color    = MaterialTheme.colorScheme.outlineVariant
        )
        Spacer(modifier = Modifier.height(4.dp))

        // Secondary navigation
        DrawerItem(
            icon    = Icons.Outlined.Info,
            label   = stringResource(R.string.nav_about),
            active  = currentScreen == Screen.ABOUT,
            onClick = { onNavigate(Screen.ABOUT) }
        )
    }
}

@Composable
private fun DrawerHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF156080))
            .padding(vertical = 24.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF156080)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter            = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier           = Modifier.size(56.dp)
            )
        }
        Text(
            text  = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val bgColor    = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
    val labelColor = if (active) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = labelColor,
            modifier           = Modifier.size(24.dp)
        )
        Text(
            text  = label,
            style = MaterialTheme.typography.labelLarge,
            color = labelColor
        )
    }
}