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

import android.content.res.Configuration
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photo.openzinkbooth.R
import com.photo.openzinkbooth.ui.components.ZinkActionButton
import com.photo.openzinkbooth.ui.viewmodel.ZinkUiState

// ---------------------------------------------------------------------------
// CameraScreen – adaptive layout
//
// Portrait (all sizes):
//   [Viewfinder]
//   [TimerRow]
//   [ShutterButton]
//
// Landscape Compact (phone landscape):
//   [Viewfinder] | [TimerColumn scrollable] | [ShutterButton]
//
// Landscape Medium/Expanded (tablet landscape):
//   [Viewfinder] | [TimerRow + ShutterButton centred]
// ---------------------------------------------------------------------------

@Composable
fun CameraScreen(
    state: ZinkUiState,
    onTimerSelected: (Int) -> Unit,
    onCapturePressed: () -> Unit,
    windowSizeClass: WindowSizeClass? = null,
    viewfinderContent: @Composable BoxScope.() -> Unit = {},
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape   = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val widthClass    = windowSizeClass?.widthSizeClass  ?: WindowWidthSizeClass.Compact
    val heightClass   = windowSizeClass?.heightSizeClass ?: WindowHeightSizeClass.Medium

    // Phone landscape: height is always Compact (< 480dp) regardless of width class.
    // This is more reliable than checking widthClass which reports Medium for phones
    // in landscape because the width exceeds 600dp.
    val isPhoneLandscape = isLandscape && heightClass == WindowHeightSizeClass.Compact

    when {
        isPhoneLandscape -> CameraLandscapeCompact(
            state, onTimerSelected, onCapturePressed, viewfinderContent, modifier
        )
        isLandscape -> CameraLandscapeWide(
            state, widthClass, onTimerSelected, onCapturePressed, viewfinderContent, modifier
        )
        else -> CameraPortrait(
            state, widthClass, onTimerSelected, onCapturePressed, viewfinderContent, modifier
        )
    }
}

// ---------------------------------------------------------------------------
// Portrait
// ---------------------------------------------------------------------------

@Composable
private fun CameraPortrait(
    state: ZinkUiState,
    widthClass: WindowWidthSizeClass,
    onTimerSelected: (Int) -> Unit,
    onCapturePressed: () -> Unit,
    viewfinderContent: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier
) {
    val hPad = when (widthClass) {
        WindowWidthSizeClass.Expanded -> 64.dp
        WindowWidthSizeClass.Medium   -> 32.dp
        else                          -> 12.dp
    }
    Column(
        modifier            = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier         = Modifier.weight(1f).padding(vertical = 12.dp, horizontal = hPad),
            contentAlignment = Alignment.Center
        ) {
            Viewfinder(
                countdown = state.countdown,
                modifier  = Modifier.fillMaxHeight().aspectRatio(2f / 3f),
                content   = viewfinderContent
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        TimerRow(
            selected   = state.timerSeconds,
            onSelect   = onTimerSelected,
            chipHeight = 40.dp,
            modifier   = Modifier.fillMaxWidth().padding(horizontal = hPad)
        )
        Spacer(modifier = Modifier.height(20.dp))
        ZinkActionButton(
            icon               = Icons.Outlined.PhotoCamera,
            label              = stringResource(R.string.camera_capture_label),
            contentDescription = stringResource(R.string.camera_capture_description),
            onClick            = onCapturePressed,
            modifier           = Modifier.padding(bottom = 16.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Landscape Compact – phone landscape
// [Viewfinder] | [Timer chips vertical scrollable] | [Shutter round]
// ---------------------------------------------------------------------------

@Composable
private fun CameraLandscapeCompact(
    state: ZinkUiState,
    onTimerSelected: (Int) -> Unit,
    onCapturePressed: () -> Unit,
    viewfinderContent: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier
) {
    val timerOptions = remember { listOf(0 to null, 3 to "3", 5 to "5", 10 to "10") }

    Row(
        modifier          = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Viewfinder
        Box(
            modifier         = Modifier
                .weight(1.2f)
                .fillMaxHeight()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Viewfinder(
                countdown = state.countdown,
                modifier  = Modifier.fillMaxHeight().aspectRatio(2f / 3f),
                content   = viewfinderContent
            )
        }

        // Timer column – vertically scrollable
        Box(
            modifier         = Modifier
                .weight(0.2f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            LazyColumn(
                modifier = Modifier
                    .width(64.dp)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Text(
                        text = stringResource(R.string.camera_timer_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                items(timerOptions.size) { i ->
                    val (seconds, label) = timerOptions[i]
                    TimerChip(
                        label = label ?: stringResource(R.string.camera_timer_off),
                        unit = if (seconds > 0) stringResource(R.string.camera_timer_seconds_unit) else "",
                        active = state.timerSeconds == seconds,
                        height = 32.dp,
                        onClick = { onTimerSelected(seconds) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Shutter button – centred, never stretched
        Box(
            modifier         = Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .padding(end = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            ZinkActionButton(
                icon               = Icons.Outlined.PhotoCamera,
                label              = stringResource(R.string.camera_capture_label),
                contentDescription = stringResource(R.string.camera_capture_description),
                onClick            = onCapturePressed
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Landscape Wide – tablet/foldable landscape
// [Viewfinder] | [TimerRow + Shutter centred vertically]
// ---------------------------------------------------------------------------

@Composable
private fun CameraLandscapeWide(
    state: ZinkUiState,
    widthClass: WindowWidthSizeClass,
    onTimerSelected: (Int) -> Unit,
    onCapturePressed: () -> Unit,
    viewfinderContent: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier
) {
    val pad = if (widthClass == WindowWidthSizeClass.Medium) 16.dp else 24.dp

    Row(
        modifier          = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier         = Modifier
                .weight(0.5f)
                .fillMaxHeight()
                .padding(pad),
            contentAlignment = Alignment.Center
        ) {
            Viewfinder(
                countdown = state.countdown,
                modifier  = Modifier.fillMaxHeight().aspectRatio(2f / 3f),
                content   = viewfinderContent
            )
        }

        Column(
            modifier            = Modifier
                .weight(0.5f)
                .fillMaxHeight()
                .padding(end = pad, top = pad, bottom = pad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            TimerRow(
                selected   = state.timerSeconds,
                onSelect   = onTimerSelected,
                chipHeight = 40.dp,
                modifier   = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(64.dp))
            ZinkActionButton(
                icon               = Icons.Outlined.PhotoCamera,
                label              = stringResource(R.string.camera_capture_label),
                contentDescription = stringResource(R.string.camera_capture_description),
                onClick            = onCapturePressed
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Viewfinder
// ---------------------------------------------------------------------------

@Composable
private fun Viewfinder(
    countdown: Int?,
    content: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF040810))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) {
        content()
        Box(
            modifier = Modifier.matchParentSize().background(
                Brush.radialGradient(colors = listOf(Color.Transparent, Color(0x33040810)))
            )
        )
        if (countdown != null) CountdownOverlay(count = countdown)
    }
}

@Composable
private fun CountdownOverlay(count: Int) {
    key(count) {
        val scale by animateFloatAsState(
            targetValue   = 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label         = "countdown_scale"
        )
        Box(
            modifier         = Modifier.fillMaxSize().background(Color(0x80040814)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text     = count.toString(),
                style    = MaterialTheme.typography.titleMedium.copy(
                    fontSize   = 96.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight(900)
                ),
                color    = Color.White,
                modifier = Modifier.scale(scale)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Timer chips
// ---------------------------------------------------------------------------

@Composable
private fun TimerRow(
    selected: Int,
    onSelect: (Int) -> Unit,
    chipHeight: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val timerOptions = remember { listOf(0 to null, 3 to "3", 5 to "5", 10 to "10") }
    Column(modifier = modifier) {
        Text(
            text      = stringResource(R.string.camera_timer_label),
            style     = MaterialTheme.typography.labelSmall,
            color     = MaterialTheme.colorScheme.onSurface,
            modifier  = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            timerOptions.forEach { (seconds, label) ->
                TimerChip(
                    label    = label ?: stringResource(R.string.camera_timer_off),
                    unit     = if (seconds > 0) stringResource(R.string.camera_timer_seconds_unit) else "",
                    active   = selected == seconds,
                    height   = chipHeight,
                    onClick  = { onSelect(seconds) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TimerChip(
    label: String,
    unit: String,
    active: Boolean,
    height: Dp = 40.dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor     = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val borderColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val labelColor  = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onBackground
    val unitColor   = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    val fontSize    = if (height <= 32.dp) 12.sp else 15.sp

    Box(
        modifier         = modifier
            .height(height)
            .clip(RoundedCornerShape(11.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(11.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
                onClick           = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment     = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge.copy(fontSize = fontSize), color = labelColor)
            if (unit.isNotEmpty()) {
                Text(
                    text  = unit,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = (fontSize.value - 3).sp),
                    color = unitColor
                )
            }
        }
    }
}