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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.photo.openzinkbooth.R
import com.photo.openzinkbooth.ui.components.FilterPicker
import com.photo.openzinkbooth.ui.components.FramePicker
import com.photo.openzinkbooth.ui.components.ZinkActionButton
import com.photo.openzinkbooth.ui.components.applyFilter
import com.photo.openzinkbooth.ui.components.applyFrameForPrint
import com.photo.openzinkbooth.ui.viewmodel.FilterType
import com.photo.openzinkbooth.ui.viewmodel.FrameType

// ---------------------------------------------------------------------------
// PreviewScreen
//
// Layout (Column, no scroll):
//   TopAppBar
//   ├── Photo         weight(1f) → shrinks when space is tight, never overlaps
//   ├── FilterPicker  fixed height, fade only on the side that has more content
//   ├── FramePicker   same
//   └── ZinkActionButton  always at bottom, no background
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    photo: Bitmap,
    selectedFilter: FilterType,
    selectedFrame: FrameType,
    selectedCustomId: String?,
    frameEntries: List<com.photo.openzinkbooth.core.database.FrameEntry>,
    onBack: () -> Unit,
    onFilterSelected: (FilterType) -> Unit,
    onFrameSelected: (FrameType) -> Unit,
    onCustomFrameSelected: (String) -> Unit,
    loadCustomBitmap: (String) -> Bitmap?,
    onPrint: (Bitmap) -> Unit,
    printWidth: Int  = 640,   // printer native width – from state.printerPrintWidth
    printHeight: Int = 960,   // printer native height – from state.printerPrintHeight
    title: String = "",
    modifier: Modifier = Modifier
) {
    val previewBitmap by remember(photo, selectedFilter, selectedFrame, selectedCustomId, printWidth, printHeight) {
        derivedStateOf {
            val filtered = applyFilter(photo, selectedFilter)
            // Render preview at the printer's exact native resolution.
            // This bitmap is sent directly to the printer — no re-scaling needed.
            if (selectedCustomId != null) {
                val filename = selectedCustomId.removePrefix("custom:")
                val frameBmp = loadCustomBitmap(filename)
                if (frameBmp != null)
                    com.photo.openzinkbooth.ui.components.applyCustomFrameForPrint(filtered, frameBmp, printWidth, printHeight)
                else
                    applyFrameForPrint(filtered, selectedFrame, printWidth, printHeight)
            } else {
                applyFrameForPrint(filtered, selectedFrame, printWidth, printHeight)
            }
        }
    }

    // Separate LazyListState per picker so fade knows scroll position
    val filterListState = rememberLazyListState()
    val frameListState  = rememberLazyListState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title.ifBlank { stringResource(R.string.preview_title) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector        = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.preview_close_description),
                            tint               = MaterialTheme.colorScheme.error
                        )
                    }
                },
                // Same color as background – no visual separation
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier        = modifier
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // ── Photo – weight(1f) so it takes remaining space and shrinks
            //    when pickers + button need room. aspectRatio clips correctly.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
                    .widthIn(max = 240.dp)
                    // aspectRatio inside a weight() container: we constrain
                    // width based on available height to keep 2:3 ratio.
                    .fillMaxHeight()
                    .wrapContentWidth()
                    .aspectRatio(printWidth.toFloat() / printHeight.toFloat())
            ) {
                Image(
                    bitmap             = previewBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.preview_title),
                    contentScale       = ContentScale.FillBounds,
                    modifier           = Modifier.fillMaxSize()
                )
            }

            // ── Filter picker ────────────────────────────────────────────────
            Text(
                text     = stringResource(R.string.preview_filter_label),
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            FilterPicker(
                photo       = photo,
                selected    = selectedFilter,
                onSelect    = onFilterSelected,
                listState   = filterListState,
                modifier    = Modifier
                    .wrapContentWidth()
                    .scrollFade(filterListState, MaterialTheme.colorScheme.background)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Frame picker ─────────────────────────────────────────────────
            Text(
                text     = stringResource(R.string.preview_frame_label),
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            FramePicker(
                photo                = photo,
                entries              = frameEntries,
                selected             = selectedFrame,
                selectedCustomId     = selectedCustomId,
                onSelectBuiltIn      = onFrameSelected,
                onSelectCustom       = onCustomFrameSelected,
                loadCustomBitmap     = loadCustomBitmap,
                listState            = frameListState,
                modifier             = Modifier
                    .wrapContentWidth()
                    .scrollFade(frameListState, MaterialTheme.colorScheme.background)
            )

            // ── Print button – always at bottom, no surface behind it ────────
            Spacer(modifier = Modifier.height(16.dp))

            ZinkActionButton(
                icon               = Icons.Outlined.Print,
                label              = stringResource(R.string.preview_print_button),
                contentDescription = stringResource(R.string.preview_print_button),
                onClick            = { onPrint(previewBitmap) },
                modifier           = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Scroll fade – draws edge gradients only where scrolling is possible.
//
// Left fade:  only when canScrollBackward (user has scrolled right)
// Right fade: only when canScrollForward  (more items to the right)
//
// This matches M3's guidance: indicate overflow only where it exists.
// ---------------------------------------------------------------------------

private fun Modifier.scrollFade(
    state: LazyListState,
    backgroundColor: Color,
    fadeWidth: Float = 48f  // dp-ish, passed as raw px from drawWithContent
): Modifier = this.drawWithContent {
    drawContent()

    val canLeft  = state.canScrollBackward
    val canRight = state.canScrollForward
    val fw = fadeWidth * density

    if (canLeft) {
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(backgroundColor, Color.Transparent),
                startX = 0f,
                endX   = fw
            )
        )
    }
    if (canRight) {
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, backgroundColor),
                startX = size.width - fw,
                endX   = size.width
            )
        )
    }
}