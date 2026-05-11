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

import androidx.activity.result.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.photo.openzinkbooth.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// PrintBar – slides in below TopBar while a print job is active.
// Spec: Surface2 bg, 1dp Accent border, radius 12dp, pulsing dot,
//       animated progress bar (3.2s linear), slide-down 0.3s entry.
// ---------------------------------------------------------------------------

@Composable
fun PrintBar(
    visible: Boolean,
    jobLabel: String,            // e.g. "Foto 1 von 3 wird gedruckt…"
    icon: ImageVector,
    printerName: String = "HP Sprocket 200",
    progress: Float = 0f,        // 0..1, used to drive the bar fill fraction
    errorMessage: String? = null, // non-null shows the error state instead of progress
    modifier: Modifier = Modifier
) {
    val isError = errorMessage != null

    AnimatedVisibility(
        visible = visible || isError,
        enter   = slideInVertically(initialOffsetY = { -it }) +
                fadeIn(animationSpec = tween(300)),
        exit    = slideOutVertically(targetOffsetY = { -it }) +
                fadeOut(animationSpec = tween(200)),
        modifier = modifier
            .padding(horizontal = 14.dp)
            .padding(bottom = 6.dp)
    ) {
        // Border and background switch to error colours when a job fails.
        val borderColor = if (isError) MaterialTheme.colorScheme.error
        else         MaterialTheme.colorScheme.primary
        val iconTint    = if (isError) MaterialTheme.colorScheme.error
        else         MaterialTheme.colorScheme.primary

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector        = if (isError) Icons.Outlined.ErrorOutline
                    else         icon,
                    contentDescription = null,
                    tint               = iconTint,
                    modifier           = Modifier.size(20.dp)
                )

                // Only show the pulsing dot while actively printing.
                if (!isError) PulsingDot()

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = if (isError) errorMessage!! else jobLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isError) MaterialTheme.colorScheme.error
                        else         MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text  = printerName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Show progress bar only while printing, not during error state.
            if (!isError) {
                PrintProgressBar(progress = progress)
            }
        }
    }
}

@Composable
private fun PulsingDot() {
    // Pulsing opacity 1→0.3→1 on a 1s loop
    val alpha by rememberInfiniteTransition(label = "dot_pulse").animateFloat(
        initialValue   = 1f,
        targetValue    = 0.3f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(1_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

@Composable
private fun PrintProgressBar(progress: Float) {
    // Animate width from 0→full over 3.2s when progress changes
    val animatedProgress by animateFloatAsState(
        targetValue    = progress.coerceIn(0f, 1f),
        animationSpec  = tween(durationMillis = 3_200, easing = LinearEasing),
        label          = "print_progress"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .fillMaxHeight()
                .clip(RoundedCornerShape(99.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
fun ZinkActionButton(
    icon: ImageVector,
    contentDescription: String?,
    label: String? = null, // Neuer Parameter für die Beschriftung
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 1. Sofortige haptische Reaktion auf Touch-down
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "button_scale"
    )

    // 2. Der Puls-Effekt (Langsamer & Dezent)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Label oberhalb des Buttons (optional)
        if (label != null) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Box(
            modifier = Modifier.size(120.dp), // Container für den Puls
            contentAlignment = Alignment.Center
        ) {
            // --- DER PULS (Halo) ---
            if (enabled && !isPressed) {
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
                )
            }

            // --- DER BUTTON ---
            Box(
                modifier = Modifier
                    .scale(scale)
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable(
                        enabled = enabled,
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        onClick()
                    },
                contentAlignment = Alignment.Center
            ) {
                // --- INNERE FLÄCHE (Doppelring-Look) ---
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(
                            if (enabled) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                        .border(
                            width = 1.dp,
                            color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
                            else Color.Transparent,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
    }
}