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
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.photo.openzinkbooth.R
import com.photo.openzinkbooth.core.database.FrameEntry
import com.photo.openzinkbooth.ui.components.FilterPicker
import com.photo.openzinkbooth.ui.components.FramePicker
import com.photo.openzinkbooth.ui.components.ZinkActionButton
import com.photo.openzinkbooth.ui.components.applyCustomFrameForPrint
import com.photo.openzinkbooth.ui.components.applyFilter
import com.photo.openzinkbooth.ui.components.applyFrameForPrint
import com.photo.openzinkbooth.ui.viewmodel.FilterType
import com.photo.openzinkbooth.ui.viewmodel.FrameType

// ---------------------------------------------------------------------------
// PreviewScreen – adaptive layout
//
// Portrait any size:
//   image (centred, maxW by widthClass) | Filter | Frame | Button (centred)
//
// Landscape Compact (phone landscape):
//   [Preview] | [Filter vertical scroll + Frame vertical scroll] | [Button]
//
// Landscape Medium/Expanded (tablet landscape):
//   [Preview] | [Filter LazyRow + Frame LazyRow] | [Button centred]
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    photo: Bitmap,
    selectedFilter: FilterType,
    selectedFrame: FrameType,
    selectedCustomId: String?,
    frameEntries: List<FrameEntry>,
    onBack: () -> Unit,
    onFilterSelected: (FilterType) -> Unit,
    onFrameSelected: (FrameType) -> Unit,
    onCustomFrameSelected: (String) -> Unit,
    loadCustomBitmap: (String) -> Bitmap?,
    onPrint: (Bitmap) -> Unit,
    printWidth: Int  = 640,
    printHeight: Int = 1002,
    windowSizeClass: WindowSizeClass? = null,
    title: String = "",
    modifier: Modifier = Modifier
) {
    val previewBitmap by remember(photo, selectedFilter, selectedFrame, selectedCustomId, printWidth, printHeight) {
        derivedStateOf {
            val filtered = applyFilter(photo, selectedFilter)
            if (selectedCustomId != null) {
                val filename = selectedCustomId.removePrefix("custom:")
                val frameBmp = loadCustomBitmap(filename)
                if (frameBmp != null)
                    applyCustomFrameForPrint(filtered, frameBmp, printWidth, printHeight)
                else
                    applyFrameForPrint(filtered, selectedFrame, printWidth, printHeight)
            } else {
                applyFrameForPrint(filtered, selectedFrame, printWidth, printHeight)
            }
        }
    }

    val filterListState = rememberLazyListState()
    val frameListState  = rememberLazyListState()

    val configuration    = LocalConfiguration.current
    val isLandscape      = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val widthClass       = windowSizeClass?.widthSizeClass  ?: WindowWidthSizeClass.Compact
    val heightClass      = windowSizeClass?.heightSizeClass ?: WindowHeightSizeClass.Medium

    // Phone landscape: height is always Compact (< 480dp).
    // widthClass reports Medium for phones in landscape (width > 600dp) so
    // we use heightClass to reliably detect phone landscape.
    val isPhoneLandscape = isLandscape && heightClass == WindowHeightSizeClass.Compact

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title.ifBlank { stringResource(R.string.preview_title) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.preview_close_description),
                            tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier       = modifier
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding).fillMaxSize()

        when {
            isPhoneLandscape -> PreviewLandscapeCompact(
                previewBitmap, photo, selectedFilter, selectedFrame, selectedCustomId,
                frameEntries, printWidth, printHeight,
                filterListState, frameListState,
                onFilterSelected, onFrameSelected, onCustomFrameSelected, loadCustomBitmap,
                { onPrint(previewBitmap) }, contentModifier
            )
            isLandscape -> PreviewLandscapeWide(
                previewBitmap, photo, selectedFilter, selectedFrame, selectedCustomId,
                frameEntries, printWidth, printHeight, widthClass,
                filterListState, frameListState,
                onFilterSelected, onFrameSelected, onCustomFrameSelected, loadCustomBitmap,
                { onPrint(previewBitmap) }, contentModifier
            )
            else -> PreviewPortrait(
                previewBitmap, photo, selectedFilter, selectedFrame, selectedCustomId,
                frameEntries, printWidth, printHeight, widthClass,
                filterListState, frameListState,
                onFilterSelected, onFrameSelected, onCustomFrameSelected, loadCustomBitmap,
                { onPrint(previewBitmap) }, contentModifier
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Portrait – all sizes, pickers and button centred
// ---------------------------------------------------------------------------

@Composable
private fun PreviewPortrait(
    previewBitmap: Bitmap,
    photo: Bitmap,
    selectedFilter: FilterType,
    selectedFrame: FrameType,
    selectedCustomId: String?,
    frameEntries: List<FrameEntry>,
    printWidth: Int,
    printHeight: Int,
    widthClass: WindowWidthSizeClass,
    filterListState: LazyListState,
    frameListState: LazyListState,
    onFilterSelected: (FilterType) -> Unit,
    onFrameSelected: (FrameType) -> Unit,
    onCustomFrameSelected: (String) -> Unit,
    loadCustomBitmap: (String) -> Bitmap?,
    onPrint: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isExpanded    = widthClass != WindowWidthSizeClass.Compact
    val hPad          = when (widthClass) {
        WindowWidthSizeClass.Expanded -> 64.dp
        WindowWidthSizeClass.Medium   -> 32.dp
        else                          -> 16.dp
    }
    val maxImageWidth = when (widthClass) {
        WindowWidthSizeClass.Expanded -> 320.dp
        WindowWidthSizeClass.Medium   -> 280.dp
        else                          -> 240.dp
    }
    // On tablet, limit picker width and centre everything
    val pickerMaxWidth = if (isExpanded) 480.dp else Dp.Unspecified
    val bg             = MaterialTheme.colorScheme.background

    Column(
        modifier            = modifier.padding(horizontal = hPad),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Preview image
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp)
                .widthIn(max = maxImageWidth)
                .fillMaxHeight()
                .wrapContentWidth()
                .aspectRatio(printWidth.toFloat() / printHeight.toFloat())
        ) {
            Image(
                bitmap             = previewBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.preview_title),
                contentScale       = ContentScale.FillBounds,
                modifier           = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))
            )
        }

        // Filter picker (centred on tablet)
        SectionLabel(stringResource(R.string.preview_filter_label))
        FilterPicker(
            photo      = photo,
            selected   = selectedFilter,
            onSelect   = onFilterSelected,
            listState  = filterListState,
            thumbWidth = 72.dp,
            modifier   = Modifier
                .widthIn(max = pickerMaxWidth)
                .scrollFade(filterListState, bg)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Frame picker (centred on tablet)
        SectionLabel(stringResource(R.string.preview_frame_label))
        FramePicker(
            photo             = photo,
            entries           = frameEntries,
            selected          = selectedFrame,
            selectedCustomId  = selectedCustomId,
            onSelectBuiltIn   = onFrameSelected,
            onSelectCustom    = onCustomFrameSelected,
            loadCustomBitmap  = loadCustomBitmap,
            listState         = frameListState,
            thumbWidth        = 72.dp,
            modifier          = Modifier
                .widthIn(max = pickerMaxWidth)
                .scrollFade(frameListState, bg)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Print button – centred, never fillMaxWidth
        ZinkActionButton(
            icon               = Icons.Outlined.Print,
            label              = stringResource(R.string.preview_print_button),
            contentDescription = stringResource(R.string.preview_print_button),
            onClick            = onPrint,
            modifier           = Modifier.padding(bottom = 16.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Landscape Compact – phone landscape
// [Preview] | [Filter vertical + Frame vertical scrollable] | [Button]
// ---------------------------------------------------------------------------

@Composable
private fun PreviewLandscapeCompact(
    previewBitmap: Bitmap,
    photo: Bitmap,
    selectedFilter: FilterType,
    selectedFrame: FrameType,
    selectedCustomId: String?,
    frameEntries: List<FrameEntry>,
    printWidth: Int,
    printHeight: Int,
    filterListState: LazyListState,
    frameListState: LazyListState,
    onFilterSelected: (FilterType) -> Unit,
    onFrameSelected: (FrameType) -> Unit,
    onCustomFrameSelected: (String) -> Unit,
    loadCustomBitmap: (String) -> Bitmap?,
    onPrint: () -> Unit,
    modifier: Modifier = Modifier
) {
    val thumbWidth = 52.dp
    val bg         = MaterialTheme.colorScheme.background

    Row(modifier = modifier) {
        // Preview image
        Box(
            modifier         = Modifier
                .weight(1.2f)
                .fillMaxHeight()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap             = previewBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.preview_title),
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .fillMaxHeight()
                    .wrapContentWidth()
                    .aspectRatio(printWidth.toFloat() / printHeight.toFloat())
                    .clip(RoundedCornerShape(4.dp))
            )
        }

        // Middle column: filter + frame vertical scrollable
        Box(
            modifier         = Modifier
                .weight(0.2f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp)
            ) {
                SectionLabel(stringResource(R.string.preview_filter_label))
                FilterPicker(
                    photo = photo,
                    selected = selectedFilter,
                    onSelect = onFilterSelected,
                    listState = filterListState,
                    thumbWidth = thumbWidth,
                    vertical = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .scrollFadeVertical(filterListState, bg)
                )

                Spacer(modifier = Modifier.height(8.dp))

                SectionLabel(stringResource(R.string.preview_frame_label))
                FramePicker(
                    photo = photo,
                    entries = frameEntries,
                    selected = selectedFrame,
                    selectedCustomId = selectedCustomId,
                    onSelectBuiltIn = onFrameSelected,
                    onSelectCustom = onCustomFrameSelected,
                    loadCustomBitmap = loadCustomBitmap,
                    listState = frameListState,
                    thumbWidth = thumbWidth,
                    vertical = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .scrollFadeVertical(frameListState, bg)
                )
            }
        }

        // Right column: print button centred
        Box(
            modifier         = Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .padding(end = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            ZinkActionButton(
                icon               = Icons.Outlined.Print,
                label              = stringResource(R.string.preview_print_button),
                contentDescription = stringResource(R.string.preview_print_button),
                onClick            = onPrint
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Landscape Wide – tablet/foldable landscape
// [Preview] | [Filter LazyRow + Frame LazyRow + Button centred]
// ---------------------------------------------------------------------------

@Composable
private fun PreviewLandscapeWide(
    previewBitmap: Bitmap,
    photo: Bitmap,
    selectedFilter: FilterType,
    selectedFrame: FrameType,
    selectedCustomId: String?,
    frameEntries: List<FrameEntry>,
    printWidth: Int,
    printHeight: Int,
    widthClass: WindowWidthSizeClass,
    filterListState: LazyListState,
    frameListState: LazyListState,
    onFilterSelected: (FilterType) -> Unit,
    onFrameSelected: (FrameType) -> Unit,
    onCustomFrameSelected: (String) -> Unit,
    loadCustomBitmap: (String) -> Bitmap?,
    onPrint: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pad = if (widthClass == WindowWidthSizeClass.Medium) 16.dp else 24.dp
    val bg  = MaterialTheme.colorScheme.background

    Row(modifier = modifier) {
        // Preview image
        Box(
            modifier         = Modifier
                .weight(0.5f)
                .fillMaxHeight()
                .padding(pad),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap             = previewBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.preview_title),
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .fillMaxHeight()
                    .wrapContentWidth()
                    .aspectRatio(printWidth.toFloat() / printHeight.toFloat())
                    .clip(RoundedCornerShape(4.dp))
            )
        }

        // Right panel: pickers + button centred
        Column(
            modifier            = Modifier
                .weight(0.5f)
                .fillMaxHeight()
                .padding(end = pad, top = pad, bottom = pad),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.weight(1f).widthIn(min = 220.dp, max = 500.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                SectionLabel(stringResource(R.string.preview_filter_label))
                FilterPicker(
                    photo      = photo,
                    selected   = selectedFilter,
                    onSelect   = onFilterSelected,
                    listState  = filterListState,
                    thumbWidth = 72.dp,
                    modifier   = Modifier.fillMaxWidth().scrollFade(filterListState, bg)
                )
                Spacer(modifier = Modifier.height(12.dp))
                SectionLabel(stringResource(R.string.preview_frame_label))
                FramePicker(
                    photo             = photo,
                    entries           = frameEntries,
                    selected          = selectedFrame,
                    selectedCustomId  = selectedCustomId,
                    onSelectBuiltIn   = onFrameSelected,
                    onSelectCustom    = onCustomFrameSelected,
                    loadCustomBitmap  = loadCustomBitmap,
                    listState         = frameListState,
                    thumbWidth        = 72.dp,
                    modifier          = Modifier.fillMaxWidth().scrollFade(frameListState, bg)
                )

                Spacer(modifier = Modifier.height(64.dp))
                // Button centred, never fillMaxWidth
                ZinkActionButton(
                    icon               = Icons.Outlined.Print,
                    label              = stringResource(R.string.preview_print_button),
                    contentDescription = stringResource(R.string.preview_print_button),
                    onClick            = onPrint
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

private fun Modifier.scrollFade(
    state: LazyListState,
    bg: Color,
    fadeWidth: Float = 48f
): Modifier = this.drawWithContent {
    drawContent()
    val fw = fadeWidth * density
    if (state.canScrollBackward) drawRect(
        brush = Brush.horizontalGradient(listOf(bg, Color.Transparent), 0f, fw)
    )
    if (state.canScrollForward) drawRect(
        brush = Brush.horizontalGradient(listOf(Color.Transparent, bg), size.width - fw, size.width)
    )
}

private fun Modifier.scrollFadeVertical(
    state: LazyListState,
    bg: Color,
    fadeHeight: Float = 32f
): Modifier = this.drawWithContent {
    drawContent()
    val fh = fadeHeight * density
    if (state.canScrollBackward) drawRect(
        brush = Brush.verticalGradient(listOf(bg, Color.Transparent), 0f, fh)
    )
    if (state.canScrollForward) drawRect(
        brush = Brush.verticalGradient(listOf(Color.Transparent, bg), size.height - fh, size.height)
    )
}