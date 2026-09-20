package com.dropshare.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private enum class AirGesture { NONE, OPEN_PALM, FIST }
private enum class AirPhase { IDLE, READY, GRABBED, DROP_CANDIDATE }
private enum class GestureOverlayKind { CARRYING, DROPPED, RECEIVED }

private data class MediaItem(
    val uri: Uri,
    val name: String,
    val mime: String,
    val isVideo: Boolean,
    val dateAdded: Long
)

private data class GestureOverlayEvent(
    val kind: GestureOverlayKind,
    val item: MediaItem?,
    val id: Long = System.nanoTime()
)

private val DropShareColors = lightColorScheme(
    primary = Color(0xFF3F51FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E9FF),
    onPrimaryContainer = Color(0xFF10184F),
    secondary = Color(0xFF008D79),
    onSecondary = Color.White,
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF171922),
    surface = Color.White,
    onSurface = Color(0xFF171922),
    surfaceVariant = Color(0xFFECEEF5),
    onSurfaceVariant = Color(0xFF5D6170),
    outlineVariant = Color(0xFFD0D3DE)
)

class DropTransfer(private val context: Context) {
    var onState: ((String) -> Unit)? = null
    var onDevices: ((List<Pair<String, String>>) -> Unit)? = null
    var onIncomingReady: (() -> Unit)? = null
    var onProgress: ((Int) -> Unit)? = null
    var onReceived: ((String) -> Unit)? = null
    var onTransferFinished: ((Boolean) -> Unit)? = null

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val endpointNames = linkedMapOf<String, String>()
    private var started = false

    fun start() {
        started = true
        onState?.invoke("Nearby ready")
        onDevices?.invoke(emptyList())
    }

    fun stop() {
        started = false
        endpointNames.clear()
        onState?.invoke("Nearby stopped")
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var transfer: DropTransfer
    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private var gestureRecognizer: GestureRecognizer? = null
    private var lastGesture = AirGesture.NONE
    private var phase = AirPhase.IDLE
    private var lastGestureAt = 0L
    private var fistConfidence = 0f
    private var palmConfidence = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transfer = DropTransfer(this)
        setContent { DropShareApp() }
    }

    override fun onDestroy() {
        gestureRecognizer?.close()
        cameraExecutor.shutdown()
        transfer.stop()
        super.onDestroy()
    }

    private fun initGestureEngine(): Boolean = try {
        gestureRecognizer?.close()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(
                com.google.mediapipe.tasks.core.BaseOptions.builder()
                    .setModelAssetPath("gesture_recognizer.task")
                    .build()
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.60f)
            .setMinHandPresenceConfidence(0.60f)
            .setMinTrackingConfidence(0.60f)
            .build()

        gestureRecognizer = GestureRecognizer.createFromOptions(this, options)
        lastGesture = AirGesture.NONE
        phase = AirPhase.IDLE
        true
    } catch (_: Exception) {
        false
    }

    private fun analyzeFrame(proxy: ImageProxy, onGesture: (AirGesture) -> Unit) {
        val recognizer = gestureRecognizer ?: run {
            proxy.close()
            return
        }

        try {
            val bitmap = proxy.toBitmap()
            val image = BitmapImageBuilder(bitmap).build()
            val ts = proxy.imageInfo.timestamp / 1_000_000L
            val result = recognizer.recognizeForVideo(image, ts)

            val category = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
            val score = category?.score() ?: 0f
            val gesture = when (category?.categoryName()) {
                "Open_Palm" -> AirGesture.OPEN_PALM
                "Closed_Fist" -> AirGesture.FIST
                else -> AirGesture.NONE
            }

            if (gesture == AirGesture.FIST) {
                fistConfidence = fistConfidence * 0.65f + score * 0.35f
                palmConfidence *= 0.65f
            } else if (gesture == AirGesture.OPEN_PALM) {
                palmConfidence = palmConfidence * 0.65f + score * 0.35f
                fistConfidence *= 0.65f
            } else {
                fistConfidence *= 0.80f
                palmConfidence *= 0.80f
            }

            val stable = when {
                fistConfidence >= 0.70f -> AirGesture.FIST
                palmConfidence >= 0.70f -> AirGesture.OPEN_PALM
                else -> AirGesture.NONE
            }

            val now = System.currentTimeMillis()
            if (stable != lastGesture && now - lastGestureAt > 220L) {
                lastGesture = stable
                lastGestureAt = now
                if (stable != AirGesture.NONE) onGesture(stable)
            }
        } catch (_: Exception) {
            // Ignore malformed frames
        } finally {
            proxy.close()
        }
    }

    private fun bindHiddenCamera(onGesture: (AirGesture) -> Unit): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        if (gestureRecognizer == null && !initGestureEngine()) return false

        return try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    val provider = future.get()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    analysis.setAnalyzer(cameraExecutor) { proxy ->
                        analyzeFrame(proxy, onGesture)
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                } catch (_: Exception) {
                    try {
                        val provider = future.get()
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()

                        analysis.setAnalyzer(cameraExecutor) { proxy ->
                            analyzeFrame(proxy, onGesture)
                        }

                        provider.unbindAll()
                        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                    } catch (_: Exception) {
                        // ignore fallback failure
                    }
                }
            }, ContextCompat.getMainExecutor(this))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun startHiddenAI(scope: CoroutineScope, onGesture: (AirGesture) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        bindHiddenCamera(onGesture)
    }

    private fun handleGesture(
        gesture: AirGesture,
        selected: MediaItem?,
        receiverReady: Boolean,
        onReceiverReady: (Boolean) -> Unit,
        onPhaseText: (String) -> Unit,
        onBusy: (Boolean) -> Unit,
        onProgress: (Int) -> Unit,
        onOverlay: (GestureOverlayEvent?) -> Unit,
        onStatus: (String) -> Unit
    ) {
        when (gesture) {
            AirGesture.OPEN_PALM -> {
                phase = if (phase == AirPhase.IDLE) AirPhase.READY else phase
                onPhaseText("Open palm detected")
                onReceiverReady(receiverReady)
            }
            AirGesture.FIST -> {
                phase = AirPhase.GRABBED
                onPhaseText("Holding selected item")
                onBusy(true)
                onProgress(25)
                onOverlay(GestureOverlayEvent(GestureOverlayKind.CARRYING, selected))
                if (selected != null) {
                    onStatus("Sending ${selected.name}")
                }
            }
            AirGesture.NONE -> {
                if (phase == AirPhase.GRABBED) {
                    onPhaseText("Gesture released")
                    onBusy(false)
                    onProgress(0)
                    onOverlay(null)
                }
            }
        }
    }

    private fun requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val component = ComponentName(this, DropShareTileService::class.java)
                TileService.requestListeningState(this, component)
            } catch (_: Exception) {
                Toast.makeText(this, "Quick settings tile is unavailable", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Quick settings not supported on this Android version", Toast.LENGTH_SHORT).show()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DropShareApp() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
        var selected by remember { mutableStateOf<MediaItem?>(null) }
        var status by remember { mutableStateOf("Starting DropShare…") }
        var devices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var progress by remember { mutableStateOf(0) }
        var receiverReady by remember { mutableStateOf(false) }
        var phaseText by remember { mutableStateOf("AI hand detection is ready") }
        var how by remember { mutableStateOf(false) }
        var mediaPermissionMissing by remember { mutableStateOf(false) }
        var cameraMissing by remember { mutableStateOf(false) }
        var nearbyPermissionMissing by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var gestureOverlay by remember { mutableStateOf<GestureOverlayEvent?>(null) }

        val selectedCurrent by rememberUpdatedState(selected)
        val receiverReadyCurrent by rememberUpdatedState(receiverReady)

        val mediaPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            mediaPermissionMissing = !hasUsableMediaPermission(context)
            if (!mediaPermissionMissing) {
                media = loadMedia(context)
                if (selected == null) selected = media.firstOrNull()
            }
        }

        val nearbyPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            nearbyPermissionMissing = !hasNearbyPermissions(context)
            if (!nearbyPermissionMissing) {
                transfer.start()
            }
        }

        val cameraPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            cameraMissing = !granted
            if (granted) {
                startHiddenAI(scope) { gesture ->
                    handleGesture(
                        gesture,
                        selectedCurrent,
                        receiverReadyCurrent,
                        { receiverReady = it },
                        { phaseText = it },
                        { busy = it },
                        { progress = it },
                        { gestureOverlay = it },
                        { status = it }
                    )
                }
            }
        }

        fun refreshMedia() {
            media = loadMedia(context)
            if (selected == null) selected = media.firstOrNull()
        }

        LaunchedEffect(Unit) {
            transfer.onState = { value ->
                scope.launch(Dispatchers.Main.immediate) {
                    status = value
                }
            }

            transfer.onDevices = { value ->
                scope.launch(Dispatchers.Main.immediate) {
                    devices = value
                }
            }

            transfer.onIncomingReady = {
                scope.launch(Dispatchers.Main.immediate) {
                    receiverReady = true
                    phaseText = "Incoming drop detected — close your hand, then open your palm to accept"
                }
            }

            transfer.onProgress = { value ->
                scope.launch(Dispatchers.Main.immediate) {
                    progress = value
                }
            }

            transfer.onReceived = { name ->
                scope.launch(Dispatchers.Main.immediate) {
                    receiverReady = false
                    busy = false
                    progress = 100
                    status = "Received $name — saved to Gallery"
                    phaseText = "AI hand detection is ready"
                }
            }

            transfer.onTransferFinished = { success ->
                scope.launch(Dispatchers.Main.immediate) {
                    busy = false
                    if (success) {
                        progress = 100
                        status = "Drop complete — file sent"
                    } else {
                        progress = 0
                        status = "Drop failed — try again"
                    }
                    phaseText = "AI hand detection is ready"
                }
            }

            if (hasNearbyPermissions(context)) {
                nearbyPermissionMissing = false
                transfer.start()
            } else {
                nearbyPermissionMissing = true
                nearbyPermissionLauncher.launch(nearbyPermissionsForCurrentApi().toTypedArray())
            }

            if (!hasUsableMediaPermission(context)) {
                mediaPermissionMissing = true
                mediaPermissionLauncher.launch(mediaPermissionsForCurrentApi().toTypedArray())
            } else {
                refreshMedia()
         
