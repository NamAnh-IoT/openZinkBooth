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

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.photo.openzinkbooth.R

// ---------------------------------------------------------------------------
// ScreenHeader – plain M3 back IconButton + centered title.
// No custom borders or backgrounds – follows M3 guidelines.
// ---------------------------------------------------------------------------

@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier          = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.camera_back_description),
                tint               = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text      = title,
            style     = MaterialTheme.typography.titleMedium,
            modifier  = Modifier.weight(1f),
            color     = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        // Mirror spacer – same width as IconButton (48dp) for visual centering
        Spacer(modifier = Modifier.size(48.dp))
    }
}