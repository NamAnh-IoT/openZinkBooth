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

package com.photo.openzinkbooth

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.photo.openzinkbooth.core.utils.LogManager
import com.photo.openzinkbooth.ui.components.*
import com.photo.openzinkbooth.ui.screens.*
import com.photo.openzinkbooth.ui.theme.ZinkBoothTheme
import com.photo.openzinkbooth.ui.viewmodel.Screen
import com.photo.openzinkbooth.ui.viewmodel.ZinkBoothViewModel

// ---------------------------------------------------------------------------
// Application class – initialises LogManager once for the process lifetime.
// ---------------------------------------------------------------------------

class ZinkBoothApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // File logging enabled in DEBUG builds only
        LogManager.init(this, enableLoggingToFile = BuildConfig.DEBUG)
    }
}

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {

    private val viewModel: ZinkBoothViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsState()
            ZinkBoothTheme(dynamicColor = state.dynamicColor) {
                ZinkBoothApp(viewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Close the diff fence so the file is valid Markdown when the app is backgrounded
        LogManager.closeMarkdownBlock()
    }
}

// ---------------------------------------------------------------------------
// Root composable
// ---------------------------------------------------------------------------

@Composable
fun ZinkBoothApp(viewModel: ZinkBoothViewModel) {
    val state   by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val camera = rememberCamera(
        useFrontCamera      = state.useFrontCamera,
        flashEnabled        = state.flashEnabled,
        shutterSoundEnabled = state.shutterSoundEnabled,
        onPhoto             = viewModel::onPhotoCaptured
    )

    // Photo picker – uses the Android Photo Picker (PickVisualMedia) which
    // opens directly in the device's image library. Available natively on
    // API 33+; Androidx backports it to API 21 via the activity-ktx library.
    // When the user has configured a custom storage Uri, the system picker
    // still opens but the user can navigate there manually.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.closeDrawer()
            viewModel.onPhotoPicked(uri)
        }
    }

    fun launchPhotoPicker() {
        photoPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // BT permission request – launched on first start
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        viewModel.onPermissionsResult(granted)
    }

    // Open app settings when permission was permanently denied
    val appSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Re-check permissions after returning from settings
        val granted = listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT
        ).all { perm ->
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, perm
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        viewModel.onPermissionsResult(granted)
    }

    fun openAppSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null)
        )
        appSettingsLauncher.launch(intent)
    }

    // Bluetooth enable request – shown when the BLUETOOTH_DISABLED pill is tapped.
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onBluetoothEnabled()
        }
    }

    fun requestEnableBluetooth() {
        val intent = android.content.Intent(
            android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE
        )
        bluetoothEnableLauncher.launch(intent)
    }

    // Check permissions on first composition
    // Camera permission request
    var cameraPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (!granted) {
            // User denied – we'll show a message and open app settings on tap
        }
    }

    LaunchedEffect(Unit) {
        // Request camera permission if not already granted
        if (!cameraPermissionGranted) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }

        // Request BT permissions
        val btPermissions = arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT
        )
        val btGranted = btPermissions.all { perm ->
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, perm
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (btGranted) {
            viewModel.onPermissionsResult(true)
        } else {
            btPermissionLauncher.launch(btPermissions)
        }
    }

    BackHandler(
        enabled = state.drawerOpen || state.screen != Screen.CAMERA
    ) {
        when {
            state.drawerOpen -> viewModel.closeDrawer()
            else             -> viewModel.navigateBack()
        }
    }

    val slideSpec = tween<IntOffset>(durationMillis = 320)
    val fadeSpec  = tween<Float>(durationMillis = 320)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        AnimatedContent(
            targetState    = state.screen,
            transitionSpec = {
                val toRoot = targetState == Screen.CAMERA
                val enter  = if (toRoot)
                    slideInHorizontally(slideSpec) { -it } + fadeIn(fadeSpec)
                else
                    slideInHorizontally(slideSpec) { it } + fadeIn(fadeSpec)
                val exit   = if (toRoot)
                    slideOutHorizontally(slideSpec) { it } + fadeOut(fadeSpec)
                else
                    slideOutHorizontally(slideSpec) { -it } + fadeOut(fadeSpec)
                enter togetherWith exit
            },
            label    = "screen_transition",
            modifier = Modifier.fillMaxSize()
        ) { screen ->
            when (screen) {

                Screen.CAMERA -> Column(modifier = Modifier.fillMaxSize()) {
                    TopBarMain(onHamburgerClick = viewModel::openDrawer)
                    TopBarStatus(
                        state               = state,
                        onPaperPillClick    = viewModel::showPaperModal,
                        onPrinterPillClick  = ::requestEnableBluetooth,
                        onOpenAppSettings   = ::openAppSettings,
                        onPrinterDisconnect = viewModel::disconnectPrinter,
                        onPrinterReconnect  = viewModel::reconnectPrinter
                    )
                    PrintBar(
                        visible  = state.printQueue.isNotEmpty(),
                        icon     = Icons.Outlined.Print,
                        jobLabel = if (state.printQueue.size == 1)
                            stringResource(R.string.print_job_label_single)
                        else
                            stringResource(R.string.print_job_label_multi, state.printQueue.size),
                        progress = state.currentPrintProgress / 100f
                    )

                    if (!cameraPermissionGranted) {
                        // Camera permission denied – prompt user to open app settings
                        Box(
                            modifier         = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier            = Modifier.padding(32.dp)
                            ) {
                                Icon(
                                    imageVector        = Icons.Outlined.CameraAlt,
                                    contentDescription = null,
                                    modifier           = Modifier.size(64.dp),
                                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text      = stringResource(R.string.camera_permission_denied),
                                    style     = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color     = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(onClick = ::openAppSettings) {
                                    Text(stringResource(R.string.camera_permission_open_settings))
                                }
                            }
                        }
                    } else {
                        CameraScreen(
                            state             = state,
                            onTimerSelected   = viewModel::setTimer,
                            onCapturePressed  = { viewModel.onCapturePressed(camera.triggerCapture) },
                            viewfinderContent = {
                                CameraPreview(
                                    previewView = camera.previewView,
                                    modifier    = Modifier.fillMaxSize()
                                )
                            }
                        )
                    }
                }

                Screen.PREVIEW -> {
                    val photo = state.capturedPhoto
                    if (photo != null) {
                        PreviewScreen(
                            photo              = photo,
                            selectedFilter     = state.selectedFilter,
                            selectedFrame      = state.selectedFrame,
                            selectedCustomId   = state.selectedCustomFrameId,
                            frameEntries       = state.frameEntries,
                            onBack             = viewModel::navigateBack,
                            onFilterSelected   = viewModel::selectFilter,
                            onFrameSelected    = viewModel::selectFrame,
                            onCustomFrameSelected = viewModel::selectCustomFrame,
                            loadCustomBitmap   = viewModel::loadCustomFrameBitmap,
                            onPrint            = viewModel::enqueuePrintFromPreview,
                            printWidth         = state.printerPrintWidth,
                            printHeight        = state.printerPrintHeight,
                            title              = if (state.previewFromPicker)
                                stringResource(R.string.preview_open_photo_title)
                            else
                                stringResource(R.string.preview_title)
                        )
                    } else {
                        LaunchedEffect(Unit) { viewModel.navigateBack() }
                    }
                }

                Screen.SETTINGS -> SettingsScreen(
                    state                    = state,
                    onBack                   = viewModel::navigateBack,
                    onNavigateToPrinter      = { viewModel.navigateTo(Screen.PRINTER) },
                    onNavigateToPrinterConfig = { viewModel.navigateTo(Screen.PRINTER_CONFIG) },
                    onNavigateToFrameManager = { viewModel.navigateTo(Screen.FRAME_MANAGER) },
                    onToggleFrontCamera      = viewModel::setFrontCamera,
                    onToggleFlash            = viewModel::setFlash,
                    onToggleDynamicColor     = viewModel::setDynamicColor,
                    onToggleShutterSound     = viewModel::setShutterSound,
                    onStorageUriSelected     = viewModel::setStorageUri
                )

                Screen.PRINTER_CONFIG -> {
                    val identity by viewModel.printerIdentity.collectAsState()
                    val config   by viewModel.printerConfig.collectAsState()
                    PrinterConfigScreen(
                        identity                = identity,
                        config                  = config,
                        uiState                 = state,
                        onBack                  = viewModel::navigateBack,
                        onApplyConfig           = viewModel::applyPrinterConfig,
                        onDisconnect            = viewModel::disconnectPrinter,
                        onSetCalibrationEnabled = viewModel::setCalibrationEnabled,
                        onSetCalibrationVScale  = viewModel::setCalibrationVScale,
                        onSetCalibrationVOffset = viewModel::setCalibrationVOffset,
                    )
                }

                Screen.FRAME_MANAGER -> FrameManagerScreen(
                    entries          = state.frameEntries,
                    onBack           = viewModel::navigateBack,
                    onSetVisible     = viewModel::setFrameVisible,
                    onMoveUp         = { i -> viewModel.saveFrameOrder(state.frameEntries.toMutableList().also { list ->
                        if (i > 0) { val tmp = list[i]; list[i] = list[i-1]; list[i-1] = tmp }
                    })},
                    onMoveDown       = { i -> viewModel.saveFrameOrder(state.frameEntries.toMutableList().also { list ->
                        if (i < list.size - 1) { val tmp = list[i]; list[i] = list[i+1]; list[i+1] = tmp }
                    })},
                    onAddCustom      = viewModel::addCustomFrame,
                    onRename         = viewModel::renameCustomFrame,
                    onDelete         = viewModel::deleteCustomFrame,
                    loadCustomBitmap = viewModel::loadCustomFrameBitmap
                )

                Screen.PRINTER -> {
                    val manualScanDevices by viewModel.manualScanDevices.collectAsState()
                    PrinterScreen(
                        state             = state,
                        manualScanDevices = manualScanDevices,
                        onBack            = viewModel::navigateBack,
                        onStartManualScan = viewModel::startManualScan,
                        onConnectDevice   = viewModel::connectManualDevice
                    )
                }

                Screen.ABOUT -> {
                    val lastPrint by viewModel.lastPrintBitmap.collectAsState()
                    AboutScreen(
                        onBack               = viewModel::navigateBack,
                        lastPrintBitmap      = lastPrint,
                        debugDryRun          = state.debugDryRun,
                        onToggleDebugDryRun  = viewModel::toggleDebugDryRun,
                        onTestPrint          = viewModel::printTestImage,
                        printWidth           = state.printerPrintWidth,
                        printHeight          = state.printerPrintHeight
                    )
                }
            }
        }

        // Drawer
        NavDrawer(
            open        = state.drawerOpen,
            state       = state,
            onClose     = viewModel::closeDrawer,
            onNavigate  = viewModel::navigateTo,
            onOpenPhoto = ::launchPhotoPicker
        )

        // Paper refill modal
        if (state.paperModalVisible) {
            PaperEmptyModal(
                onDismiss = viewModel::hidePaperModal,
                onConfirm = viewModel::confirmPaperRefill
            )
        }
    }
}