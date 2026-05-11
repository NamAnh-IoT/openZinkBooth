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

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
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
// Flash behaviour:
//   - Back camera              → hardware flash via FLASH_MODE_ON / OFF
//   - Front camera + API 34+  → FLASH_MODE_SCREEN: maximises screen brightness
//                                AND shows a full-screen white overlay via
//                                [CameraHandle.screenFlashActive] state that
//                                MainActivity observes and renders on top of
//                                the entire UI.
//   - Front camera + API < 34 → flash option hidden in SettingsScreen;
//                                flashEnabled is always treated as false here.
// ---------------------------------------------------------------------------

private const val TAG = "CameraIntegration"

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Holds everything MainActivity needs to drive the camera.
 *
 * [screenFlashActive] is set to true by [buildScreenFlash].apply() and back
 * to false by .clear(). MainActivity should render a full-screen white overlay
 * whenever this is true so the front camera has a bright white light source.
 */
data class CameraHandle(
    val triggerCapture: () -> Unit,
    val previewView: PreviewView,
    val screenFlashActive: State<Boolean>
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

    // Shared state: true while the screen-flash overlay must be visible.
    // Driven by buildScreenFlash; read by MainActivity to show the overlay.
    val screenFlashActive = remember { mutableStateOf(false) }

    // Determine the effective flash mode for this camera / API combination:
    //   back camera              → FLASH_MODE_ON or OFF
    //   front camera + API 34+  → FLASH_MODE_SCREEN when enabled
    //   front camera + API < 34 → always OFF (option hidden in UI)
    val effectiveFlashMode = when {
        !useFrontCamera ->
            if (flashEnabled) ImageCapture.FLASH_MODE_ON
            else              ImageCapture.FLASH_MODE_OFF
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            if (flashEnabled) ImageCapture.FLASH_MODE_SCREEN
            else              ImageCapture.FLASH_MODE_OFF
        else -> ImageCapture.FLASH_MODE_OFF
    }

    // PreviewView created once; stable across recompositions.
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType          = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // ImageCapture use-case – recreated when lens direction or flash mode changes.
    val imageCapture = remember(useFrontCamera, effectiveFlashMode) {
        val builder = ImageCapture.Builder()
            .setFlashMode(effectiveFlashMode)

        // Attach the ScreenFlash implementation when using FLASH_MODE_SCREEN.
        // It controls both screen brightness and the white overlay state.
        if (useFrontCamera &&
            effectiveFlashMode == ImageCapture.FLASH_MODE_SCREEN &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            builder.setScreenFlash(buildScreenFlash(context, screenFlashActive))
        }

        builder.build()
    }

    // Single bind for both use-cases – avoids the unbindAll() conflict.
    LaunchedEffect(useFrontCamera, effectiveFlashMode) {
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

    return remember(trigger, previewView, screenFlashActive) {
        CameraHandle(
            triggerCapture    = trigger,
            previewView       = previewView,
            screenFlashActive = screenFlashActive
        )
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

/**
 * Builds an [ImageCapture.ScreenFlash] that:
 *   1. Maximises screen brightness via the Window API.
 *   2. Sets [screenFlashActive] to true so MainActivity renders the white overlay.
 *
 * [clear] reverses both actions once CameraX has captured the frame.
 *
 * Requires API 34 (UPSIDE_DOWN_CAKE).
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun buildScreenFlash(
    context: Context,
    screenFlashActive: MutableState<Boolean>
): ImageCapture.ScreenFlash {
    return object : ImageCapture.ScreenFlash {
        // Stores the brightness value that was active before the flash started so
        // clear() can restore it exactly rather than resetting to a generic default.
        // -1.0f is the system default (BRIGHTNESS_OVERRIDE_NONE).
        private var previousBrightness: Float = -1.0f

        override fun apply(
            expirationTimeMillis: Long,
            listener: ImageCapture.ScreenFlashListener
        ) {
            val activity = context as? Activity
            activity?.window?.let { window ->
                // Save the current brightness before overriding it so clear() can
                // restore the exact value the user had before the flash triggered.
                previousBrightness = window.attributes.screenBrightness

                val lp = window.attributes
                lp.screenBrightness = 1f   // maximum brightness (same as BRIGHTNESS_OVERRIDE_FULL)
                window.attributes = lp
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            // Show the white overlay in the UI (observed by MainActivity).
            screenFlashActive.value = true

            // Signal CameraX that the screen is ready for the flash exposure.
            listener.onCompleted()
        }

        override fun clear() {
            // Hide the white overlay.
            screenFlashActive.value = false

            // Restore the exact brightness value that was active before apply() was
            // called, as recommended by the Android screen flash guidelines.
            val activity = context as? Activity
            activity?.window?.let { window ->
                val lp = window.attributes
                lp.screenBrightness = previousBrightness
                window.attributes = lp
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

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

    // Correct EXIF rotation and mirror the front camera image horizontally
    // so the captured photo matches what the user saw in the preview.
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