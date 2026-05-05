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

import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photo.openzinkbooth.R
import com.photo.openzinkbooth.ui.components.ZinkActionButton
import com.photo.openzinkbooth.ui.viewmodel.ZinkUiState


@Composable
fun CameraScreen(
    state: ZinkUiState,
    onTimerSelected: (Int) -> Unit,
    onCapturePressed: () -> Unit,
    // Slot for the real CameraX PreviewView; Preview composable for tests
    viewfinderContent: @Composable BoxScope.() -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Viewfinder(
                countdown    = state.countdown,

                modifier     = Modifier
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f),
                content      = viewfinderContent
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Timer chip row
        TimerRow(
            selected = state.timerSeconds,
            onSelect = onTimerSelected,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
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
        // Real camera preview (or placeholder in preview mode)
        content()

        // Radial gradient overlay over the camera feed
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color(0x33040810))
                    )
                )
        )

        // Countdown overlay
        if (countdown != null) {
            CountdownOverlay(count = countdown)
        }
    }
}

@Composable
private fun CountdownOverlay(count: Int) {
    // Use key() so the scale animation restarts on every count change
    key(count) {
        val scale by animateFloatAsState(
            targetValue   = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness    = Spring.StiffnessMedium
            ),
            label = "countdown_scale"
        )

        // fillMaxSize() works here because CountdownOverlay is called inside
        // a Box{} scope in Viewfinder, which gives it full parent dimensions.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x80040814)),
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
    modifier: Modifier = Modifier
) {
    val timerOptions = remember {
        listOf(
            0 to null,
            3 to "3",
            5 to "5",
            10 to "10"
        )
    }

    Column(modifier = modifier) {
        Text(
            text     = stringResource(R.string.camera_timer_label),
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier            = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            timerOptions.forEach { (seconds, label) ->
                val displayLabel = label ?: stringResource(R.string.camera_timer_off)
                TimerChip(
                    label    = displayLabel,
                    unit     = if (seconds > 0) stringResource(R.string.camera_timer_seconds_unit) else "",
                    active   = selected == seconds,
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor     = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val borderColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val labelColor  = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onBackground
    val unitColor   = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant


    Box(
        modifier = modifier
            .height(40.dp)
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
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = labelColor)
            if (unit.isNotEmpty()) {
                Text(
                    text     = unit,
                    style    = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color    = unitColor,
                )
            }
        }
    }
}

