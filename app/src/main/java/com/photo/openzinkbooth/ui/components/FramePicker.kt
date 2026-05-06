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

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.photo.openzinkbooth.core.database.FrameEntry
import com.photo.openzinkbooth.ui.viewmodel.FrameType
import kotlin.math.min
import kotlin.random.Random

// ---------------------------------------------------------------------------
// FramePicker – horizontal row of frame thumbnails.
// ---------------------------------------------------------------------------

// Thumbnail dimensions – 2:3 ratio matching the Zink paper aspect ratio.
// This ensures the thumbnail always looks identical to the final print.
private const val FRAME_THUMB_W = 150
private const val FRAME_THUMB_H = 225

// Zink paper aspect ratio (width:height = 2:3).
// ALL frame operations crop the source to this ratio first so that
// thumbnails, the preview image, and the print output all match exactly.
private const val ZINK_RATIO_W = 2f
private const val ZINK_RATIO_H = 3f

@Composable
fun FramePicker(
    photo: Bitmap,
    entries: List<FrameEntry>,
    selected: FrameType,
    selectedCustomId: String?,
    onSelectBuiltIn: (FrameType) -> Unit,
    onSelectCustom: (String) -> Unit,
    loadCustomBitmap: (String) -> Bitmap?,
    listState: LazyListState = rememberLazyListState(),
    thumbWidth: androidx.compose.ui.unit.Dp = 72.dp,
    vertical: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cropped = remember(photo) {
        centerCropToRatio(photo, ZINK_RATIO_W, ZINK_RATIO_H)
    }

    if (vertical) {
        androidx.compose.foundation.lazy.LazyColumn(
            state               = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding      = PaddingValues(vertical = 2.dp),
            modifier            = modifier
        ) {
            item {
                FrameThumbBuiltIn(
                    photo      = cropped,
                    frame      = FrameType.NONE,
                    active     = selected == FrameType.NONE && selectedCustomId == null,
                    thumbWidth = thumbWidth,
                    onClick    = { onSelectBuiltIn(FrameType.NONE) }
                )
            }
            items(entries.filter { it.visible }) { entry ->
                when (entry) {
                    is FrameEntry.BuiltIn -> FrameThumbBuiltIn(
                        photo      = cropped,
                        frame      = entry.frameType,
                        active     = selected == entry.frameType && selectedCustomId == null,
                        thumbWidth = thumbWidth,
                        onClick    = { onSelectBuiltIn(entry.frameType) }
                    )
                    is FrameEntry.Custom -> {
                        val bitmap = remember(entry.filename) { loadCustomBitmap(entry.filename) }
                        if (bitmap != null) {
                            FrameThumbCustom(
                                photo      = cropped,
                                overlay    = bitmap,
                                label      = entry.filename,
                                active     = selectedCustomId == entry.id,
                                thumbWidth = thumbWidth,
                                onClick    = { onSelectCustom(entry.id) }
                            )
                        }
                    }
                }
            }
        }
    } else {
        Column(modifier = modifier) {
            LazyRow(
                state                 = listState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding        = PaddingValues(horizontal = 2.dp)
            ) {
                item {
                    FrameThumbBuiltIn(
                        photo      = cropped,
                        frame      = FrameType.NONE,
                        active     = selected == FrameType.NONE && selectedCustomId == null,
                        thumbWidth = thumbWidth,
                        onClick    = { onSelectBuiltIn(FrameType.NONE) }
                    )
                }
                items(entries.filter { it.visible }) { entry ->
                    when (entry) {
                        is FrameEntry.BuiltIn -> FrameThumbBuiltIn(
                            photo      = cropped,
                            frame      = entry.frameType,
                            active     = selected == entry.frameType && selectedCustomId == null,
                            thumbWidth = thumbWidth,
                            onClick    = { onSelectBuiltIn(entry.frameType) }
                        )
                        is FrameEntry.Custom -> {
                            val bitmap = remember(entry.filename) { loadCustomBitmap(entry.filename) }
                            if (bitmap != null) {
                                FrameThumbCustom(
                                    photo      = cropped,
                                    overlay    = bitmap,
                                    label      = entry.filename,
                                    active     = selectedCustomId == entry.id,
                                    thumbWidth = thumbWidth,
                                    onClick    = { onSelectCustom(entry.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameThumbBuiltIn(
    photo: Bitmap,
    frame: FrameType,
    active: Boolean,
    thumbWidth: androidx.compose.ui.unit.Dp = 72.dp,
    onClick: () -> Unit
) {
    val composed = remember(photo, frame) { composeFrameThumbnail(photo, frame) }
    FrameThumbShell(
        bitmap     = composed,
        label      = stringResource(frame.labelRes),
        active     = active,
        thumbWidth = thumbWidth,
        onClick    = onClick
    )
}

@Composable
private fun FrameThumbCustom(
    photo: Bitmap,
    overlay: Bitmap,
    label: String,
    active: Boolean,
    thumbWidth: androidx.compose.ui.unit.Dp = 72.dp,
    onClick: () -> Unit
) {
    val composed = remember(photo, overlay) { applyCustomFrameThumbnail(photo, overlay) }
    FrameThumbShell(bitmap = composed, label = label, active = active, thumbWidth = thumbWidth, onClick = onClick)
}

@Composable
private fun FrameThumbShell(
    bitmap: Bitmap,
    label: String,
    active: Boolean,
    thumbWidth: androidx.compose.ui.unit.Dp = 72.dp,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(if (active) 1.05f else 1f, label = "scale")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .width(thumbWidth)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
            .scale(scale)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .border(
                    width = if (active) 3.dp else 1.dp,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                )
        ) {
            Image(
                bitmap             = bitmap.asImageBitmap(),
                contentDescription = label,
                contentScale       = ContentScale.Fit,
                modifier           = Modifier.fillMaxSize()
            )
            if (active) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector        = androidx.compose.material.icons.Icons.Default.Check,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.onPrimary,
                        modifier           = Modifier.size(12.dp)
                    )
                }
            }
        }
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Bold else null
            ),
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// Public API
//
// All functions crop to 2:3 first, then apply the frame.
// This guarantees thumbnail == preview == print output.
// ---------------------------------------------------------------------------

/**
 * Produces a thumbnail-sized bitmap with the frame applied.
 * The source is cropped to the Zink 2:3 ratio before compositing.
 */
fun composeFrameThumbnail(source: Bitmap, frame: FrameType): Bitmap {
    val cropped = centerCropToRatio(source, ZINK_RATIO_W, ZINK_RATIO_H)
    return composeFrameInternal(cropped, frame, FRAME_THUMB_W, FRAME_THUMB_H)
}

/**
 * Produces a print-sized bitmap with the frame applied.
 * The source is rotated to portrait, cropped to 2:3, then framed.
 * Pass the printer's native resolution as [targetW] × [targetH].
 */
fun applyFrameForPrint(source: Bitmap, frame: FrameType, targetW: Int, targetH: Int): Bitmap {
    val cropped = centerCropToRatio(source, ZINK_RATIO_W, ZINK_RATIO_H)
    return composeFrameInternal(cropped, frame, targetW, targetH)
}

/** Overlays a custom PNG frame onto the photo at thumbnail size. */
fun applyCustomFrameThumbnail(photo: Bitmap, frameBitmap: Bitmap): Bitmap {
    val cropped = centerCropToRatio(photo, ZINK_RATIO_W, ZINK_RATIO_H)
    return applyCustomFrame(cropped, frameBitmap, FRAME_THUMB_W, FRAME_THUMB_H)
}

/** Overlays a custom PNG frame onto the photo at print size. */
fun applyCustomFrameForPrint(photo: Bitmap, frameBitmap: Bitmap, targetW: Int, targetH: Int): Bitmap {
    val cropped = centerCropToRatio(photo, ZINK_RATIO_W, ZINK_RATIO_H)
    return applyCustomFrame(cropped, frameBitmap, targetW, targetH)
}

private fun applyCustomFrame(photo: Bitmap, frameBitmap: Bitmap, targetW: Int, targetH: Int): Bitmap {
    val result = centerCropTo(photo, targetW, targetH).copy(Bitmap.Config.ARGB_8888, true)
    val canvas = AndroidCanvas(result)
    val scaled = Bitmap.createScaledBitmap(frameBitmap, targetW, targetH, true)
    canvas.drawBitmap(scaled, 0f, 0f, null)
    return result
}

// ---------------------------------------------------------------------------
// Frame compositing – source is always already cropped to 2:3 at this point
// ---------------------------------------------------------------------------

private fun composeFrameInternal(
    // [source] is pre-cropped to 2:3 by all callers above
    source: Bitmap,
    frame: FrameType,
    targetW: Int,
    targetH: Int
): Bitmap = when (frame) {

    FrameType.NONE -> centerCropTo(source, targetW, targetH)

    // ── Classic – Polaroid style ─────────────────────────────────────────────
    // White border on all sides, larger at the bottom (like a Polaroid).
    // The photo fills the inner area with scale-to-fill + center-crop so
    // there is never any white gap inside the photo area.
    FrameType.CLASSIC -> {
        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(result)
        canvas.drawColor(AndroidColor.WHITE)

        val borderSide = targetW * 0.05f
        val borderTop  = borderSide          // same on left, right and top
        val borderBot  = targetH * 0.18f

        val availW = (targetW - borderSide * 2).toInt()
        val availH = (targetH - borderTop - borderBot).toInt()

        // Scale-to-fill (cover): no gaps, center-crop the overflow
        val scaleX = availW.toFloat() / source.width
        val scaleY = availH.toFloat() / source.height
        val scale  = maxOf(scaleX, scaleY)

        val srcCropX = ((source.width  - availW  / scale) / 2f).toInt().coerceAtLeast(0)
        val srcCropY = ((source.height - availH / scale) / 2f).toInt().coerceAtLeast(0)
        val srcCropW = (availW / scale).toInt().coerceAtMost(source.width  - srcCropX)
        val srcCropH = (availH / scale).toInt().coerceAtMost(source.height - srcCropY)

        val cropped = Bitmap.createBitmap(source, srcCropX, srcCropY, srcCropW, srcCropH)
        val scaled  = Bitmap.createScaledBitmap(cropped, availW, availH, true)

        canvas.drawBitmap(scaled, borderSide, borderTop, null)
        result
    }

    // ── Elegant – black outer border + white inner border ───────────────────
    FrameType.ELEGANT -> {
        val cropped = centerCropTo(source, targetW, targetH)
        val result  = cropped.copy(Bitmap.Config.ARGB_8888, true)
        val canvas  = AndroidCanvas(result)

        val outerThick = targetW * 0.045f
        val innerThick = targetW * 0.018f
        val gap        = outerThick + innerThick * 0.5f

        val paintOuter = AndroidPaint().apply {
            color       = AndroidColor.BLACK
            style       = AndroidPaint.Style.STROKE
            strokeWidth = outerThick
            isAntiAlias = true
        }
        var inset = outerThick / 2f
        canvas.drawRect(RectF(inset, inset, targetW - inset, targetH - inset), paintOuter)

        val paintInner = AndroidPaint().apply {
            color       = AndroidColor.WHITE
            style       = AndroidPaint.Style.STROKE
            strokeWidth = innerThick
            isAntiAlias = true
        }
        inset = gap
        canvas.drawRect(RectF(inset, inset, targetW - inset, targetH - inset), paintInner)
        result
    }

    // ── Filmstrip ────────────────────────────────────────────────────────────
    FrameType.FILMSTRIP -> {
        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(result)

        val stripW = targetW * 0.12f
        val holeW  = stripW * 0.55f
        val holeH  = holeW * 0.65f
        val holeR  = holeH * 0.25f

        val paintBlack = AndroidPaint().apply { color = AndroidColor.BLACK; isAntiAlias = true }
        canvas.drawRect(0f, 0f, stripW, targetH.toFloat(), paintBlack)
        canvas.drawRect(targetW - stripW, 0f, targetW.toFloat(), targetH.toFloat(), paintBlack)

        val photoW = targetW - stripW * 2
        val scaled = centerCropTo(source, photoW.toInt(), targetH)
        canvas.drawBitmap(scaled, stripW, 0f, null)

        val paintHole  = AndroidPaint().apply { color = AndroidColor.WHITE; isAntiAlias = true }
        val holeCount  = (targetH / (holeH * 2.2f)).toInt().coerceAtLeast(4)
        val spacing    = targetH.toFloat() / holeCount
        val holeX      = (stripW - holeW) / 2f

        for (i in 0 until holeCount) {
            val cy     = spacing * i + spacing / 2f
            val top    = cy - holeH / 2f
            val bottom = cy + holeH / 2f
            canvas.drawRoundRect(RectF(holeX, top, holeX + holeW, bottom), holeR, holeR, paintHole)
            val rx = targetW - stripW + holeX
            canvas.drawRoundRect(RectF(rx, top, rx + holeW, bottom), holeR, holeR, paintHole)
        }
        result
    }

    // ── Confetti ─────────────────────────────────────────────────────────────
    FrameType.CONFETTI -> {
        val cropped = centerCropTo(source, targetW, targetH)
        val result  = cropped.copy(Bitmap.Config.ARGB_8888, true)
        val canvas  = AndroidCanvas(result)

        val colors = intArrayOf(
            AndroidColor.rgb(255, 80,  80),
            AndroidColor.rgb(255, 200, 40),
            AndroidColor.rgb(60,  180, 255),
            AndroidColor.rgb(80,  220, 120),
            AndroidColor.rgb(220, 80,  255),
            AndroidColor.rgb(255, 140, 40),
        )
        val rng    = Random(42)
        val margin = targetW * 0.13f
        val count  = (targetW * targetH * 0.00018f).toInt().coerceIn(40, 180)
        val paint  = AndroidPaint().apply { isAntiAlias = true; style = AndroidPaint.Style.FILL }

        repeat(count) {
            paint.color = colors[rng.nextInt(colors.size)]
            val r = targetW * rng.nextFloat(0.008f, 0.022f)
            val x: Float; val y: Float
            when (rng.nextInt(4)) {
                0    -> { x = rng.nextFloat(0f, targetW.toFloat()); y = rng.nextFloat(0f, margin) }
                1    -> { x = rng.nextFloat(0f, targetW.toFloat()); y = rng.nextFloat(targetH - margin, targetH.toFloat()) }
                2    -> { x = rng.nextFloat(0f, margin); y = rng.nextFloat(0f, targetH.toFloat()) }
                else -> { x = rng.nextFloat(targetW - margin, targetW.toFloat()); y = rng.nextFloat(0f, targetH.toFloat()) }
            }
            if (rng.nextBoolean()) {
                canvas.drawCircle(x, y, r, paint)
            } else {
                canvas.save()
                canvas.rotate(rng.nextFloat(0f, 180f), x, y)
                canvas.drawRect(RectF(x - r, y - r * 0.5f, x + r, y + r * 0.5f), paint)
                canvas.restore()
            }
        }
        result
    }

    // ── Hearts ───────────────────────────────────────────────────────────────
    FrameType.HEARTS -> {
        val cropped = centerCropTo(source, targetW, targetH)
        val result  = cropped.copy(Bitmap.Config.ARGB_8888, true)
        val canvas  = AndroidCanvas(result)

        val rng    = Random(7)
        val margin = targetW * 0.13f
        val count  = (targetW * targetH * 0.00014f).toInt().coerceIn(30, 120)
        val paint  = AndroidPaint().apply { isAntiAlias = true; style = AndroidPaint.Style.FILL }

        repeat(count) {
            paint.color = when (rng.nextInt(3)) {
                0    -> AndroidColor.rgb(210, 30,  60)
                1    -> AndroidColor.rgb(255, 90,  130)
                else -> AndroidColor.rgb(255, 170, 190)
            }
            val size = targetW * rng.nextFloat(0.018f, 0.055f)
            val x: Float; val y: Float
            when (rng.nextInt(4)) {
                0    -> { x = rng.nextFloat(0f, targetW.toFloat()); y = rng.nextFloat(0f, margin) }
                1    -> { x = rng.nextFloat(0f, targetW.toFloat()); y = rng.nextFloat(targetH - margin, targetH.toFloat()) }
                2    -> { x = rng.nextFloat(0f, margin);            y = rng.nextFloat(0f, targetH.toFloat()) }
                else -> { x = rng.nextFloat(targetW - margin, targetW.toFloat()); y = rng.nextFloat(0f, targetH.toFloat()) }
            }
            canvas.save()
            canvas.rotate(rng.nextFloat(-20f, 20f), x, y)
            drawHeart(canvas, x, y, size, paint)
            canvas.restore()
        }
        result
    }
}

// ---------------------------------------------------------------------------
// Shape helpers
// ---------------------------------------------------------------------------

private fun drawHeart(canvas: AndroidCanvas, cx: Float, cy: Float, size: Float, paint: AndroidPaint) {
    val path = AndroidPath()
    val s = size / 2f
    path.moveTo(cx, cy + s * 0.35f)
    path.cubicTo(cx - s * 1.5f, cy - s * 0.6f, cx - s * 2f, cy + s * 0.8f, cx, cy + s * 1.8f)
    path.cubicTo(cx + s * 2f, cy + s * 0.8f, cx + s * 1.5f, cy - s * 0.6f, cx, cy + s * 0.35f)
    path.close()
    canvas.drawPath(path, paint)
}

// ---------------------------------------------------------------------------
// Geometry helpers
// ---------------------------------------------------------------------------

/**
 * Center-crops [source] to the given aspect ratio (w:h) without scaling to
 * a fixed pixel size. Used to normalise any input photo to 2:3 before
 * frame compositing so that thumbnails, preview and print all match.
 */
private fun centerCropToRatio(source: Bitmap, ratioW: Float, ratioH: Float): Bitmap {
    val srcW = source.width.toFloat()
    val srcH = source.height.toFloat()

    // How large a 2:3 rectangle fits inside the source?
    val scale = minOf(srcW / ratioW, srcH / ratioH)
    val cropW = (ratioW * scale).toInt().coerceAtMost(source.width)
    val cropH = (ratioH * scale).toInt().coerceAtMost(source.height)
    val cropX = (source.width  - cropW) / 2
    val cropY = (source.height - cropH) / 2
    return Bitmap.createBitmap(source, cropX, cropY, cropW, cropH)
}

/**
 * Scale-to-fill + center-crop [source] to exactly [targetW] × [targetH].
 * Used inside frame compositing where [source] is already 2:3.
 */
private fun centerCropTo(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
    if (source.width == targetW && source.height == targetH) return source
    val scale   = maxOf(targetW.toFloat() / source.width, targetH.toFloat() / source.height)
    val scaledW = (source.width  * scale).toInt()
    val scaledH = (source.height * scale).toInt()
    val scaled  = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)
    val cropX   = (scaledW - targetW) / 2
    val cropY   = (scaledH - targetH) / 2
    return Bitmap.createBitmap(scaled, cropX, cropY, targetW, targetH)
}

private fun Random.nextFloat(from: Float, until: Float) =
    from + nextFloat() * (until - from)