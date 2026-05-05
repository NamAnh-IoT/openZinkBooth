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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

// ---------------------------------------------------------------------------
// CameraX integration – single file, no state leaks into screens.
//
// Key design decision: CameraPreview and rememberImageCapture share the same
// ProcessCameraProvider and the same bindCamera call. Both use-cases
// (Preview + ImageCapture) are bound together so they never fight over
// camera ownership via unbindAll().
//
// Usage in MainActivity:
//
//   val (triggerCapture, previewView) = rememberCamera(
//       useFrontCamera = state.useFrontCamera,
//       onPhoto        = viewModel::onPhotoCaptured
//   )
//
//   CameraScreen(
//       onCapturePressed  = { viewModel.onCapturePressed(triggerCapture) },
//       viewfinderContent = { CameraPreview(previewView) }
//   )
// ---------------------------------------------------------------------------

private const val TAG = "CameraIntegration"

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

data class CameraHandle(
    val triggerCapture: () -> Unit,
    val previewView: PreviewView
)

/**
 * Creates and remembers a [CameraHandle] that binds both Preview and
 * ImageCapture use-cases in a single CameraX session.
 *
 * Re-binds automatically when [useFrontCamera] or [flashEnabled] changes.
 * [onPhoto] is invoked on the main thread with an upright [Bitmap].
 */
@Composable
fun rememberCamera(
    useFrontCamera: Boolean,
    flashEnabled: Boolean,
    shutterSoundEnabled: Boolean,
    onPhoto: (Bitmap) -> Unit
): CameraHandle {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor       = remember { ContextCompat.getMainExecutor(context) }

    // PreviewView created once; stable across recompositions
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType          = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // ImageCapture use-case – recreated when lens direction or flash mode changes
    val imageCapture = remember(useFrontCamera, flashEnabled) {
        ImageCapture.Builder()
            .setFlashMode(
                if (flashEnabled) ImageCapture.FLASH_MODE_ON
                else              ImageCapture.FLASH_MODE_OFF
            )
            .build()
    }

    // Single bind for both use-cases – avoids the unbindAll() conflict
    LaunchedEffect(useFrontCamera, flashEnabled) {
        bindCamera(
            context        = context,
            lifecycleOwner = lifecycleOwner,
            previewView    = previewView,
            imageCapture   = imageCapture,
            useFrontCamera = useFrontCamera
        )
    }

    val trigger = remember(imageCapture, useFrontCamera, shutterSoundEnabled, onPhoto) {
        { capturePhoto(imageCapture, executor, useFrontCamera, shutterSoundEnabled, onPhoto) }
    }

    return remember(trigger, previewView) {
        CameraHandle(triggerCapture = trigger, previewView = previewView)
    }
}

/**
 * Renders the live camera preview using the [PreviewView] from [rememberCamera].
 * Must be called inside the viewfinderContent slot of CameraScreen.
 */
@Composable
fun CameraPreview(
    previewView: PreviewView,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory  = { previewView },
        modifier = modifier,
        update   = {}   // PreviewView is already configured by rememberCamera
    )
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

private fun bindCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    imageCapture: ImageCapture,
    useFrontCamera: Boolean
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()
        val selector = if (useFrontCamera)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }, ContextCompat.getMainExecutor(context))
}

private fun capturePhoto(
    imageCapture: ImageCapture,
    executor: Executor,
    useFrontCamera: Boolean,
    shutterSoundEnabled: Boolean,
    onPhoto: (Bitmap) -> Unit
) {
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                // Play shutter sound manually so we can suppress it when disabled.
                // CameraX does not expose a built-in mute flag for in-memory capture,
                // so we control the sound ourselves via MediaActionSound.
                if (shutterSoundEnabled) {
                    android.media.MediaActionSound().play(android.media.MediaActionSound.SHUTTER_CLICK)
                }
                val bitmap = imageProxyToBitmap(image, useFrontCamera)
                image.close()
                if (bitmap != null) onPhoto(bitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed: ${exception.message}", exception)
            }
        }
    )
}

private fun imageProxyToBitmap(image: ImageProxy, useFrontCamera: Boolean): Bitmap? {
    val buffer = image.planes[0].buffer
    val bytes  = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

    // Build a matrix that corrects EXIF rotation and – for the front camera –
    // flips horizontally so the captured image matches what the user saw in
    // the preview (front camera preview is mirrored, captured JPEG is not).
    val matrix = Matrix()
    if (image.imageInfo.rotationDegrees != 0) {
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
    }
    if (useFrontCamera) {
        matrix.postScale(-1f, 1f, raw.width / 2f, raw.height / 2f)
    }

    return if (!matrix.isIdentity) {
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    } else raw
}