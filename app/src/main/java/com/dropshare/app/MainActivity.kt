package com.dropshare.app

import android.Manifest
import android.content.ContentValues
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.drawable.Icon
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.service.quicksettings.TileService
import android.widget.Toast
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private enum class AirGesture { NONE, OPEN_PALM, FIST }
private enum class AirPhase { IDLE, READY, GRABBED, DROP_CANDIDATE }
private enum class GestureOverlayKind { CARRYING, DROPPED, RECEIVED }
private data class GestureOverlayEvent(val kind: GestureOverlayKind, val item: MediaItem?, val id: Long = System.nanoTime())
private data class MediaItem(val uri: Uri, val name: String, val mime: String, val isVideo: Boolean, val dateAdded: Long)

private val DropShareColors = lightColorScheme(
    primary = Color(0xFF3F51FF), onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E9FF), onPrimaryContainer = Color(0xFF10184F),
    secondary = Color(0xFF008D79), onSecondary = Color.White,
    background = Color(0xFFF7F8FC), onBackground = Color(0xFF171922),
    surface = Color.White, onSurface = Color(0xFF171922),
    surfaceVariant = Color(0xFFECEEF5), onSurfaceVariant = Color(0xFF5D6170),
    outlineVariant = Color(0xFFD0D3DE)
)

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
            .setBaseOptions(com.google.mediapipe.tasks.core.BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
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
    } catch (_: Exception) { false }

    private fun analyzeFrame(proxy: ImageProxy, onGesture: (AirGesture) -> Unit) {
        val recognizer = gestureRecognizer ?: run { proxy.close(); return }
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
            // Temporal smoothing: a gesture must be seen repeatedly with useful confidence.
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
            // One malformed frame must never stop the continuous analyzer.
        } finally { proxy.close() }
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
                    analysis.setAnalyzer(cameraExecutor) { analyzeFrame(it, onGesture) }
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                } catch (_: Exception) {
                    try {
                        val provider = future.get()
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                        analysis.setAnalyzer(cameraExecutor) { analyzeFrame(it, onGesture) }
                        provider.unbindAll()
                        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                    } catch (_: Exception) { }
                }
            }, ContextCompat.getMainExecutor(this))
            true
        } catch (_: Exception) { false }
    }

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
        var nearbyPermissionMissing by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var gestureOverlay by remember { mutableStateOf<GestureOverlayEvent?>(null) }
        val selectedCurrent by rememberUpdatedState(selected)
        val receiverReadyCurrent by rememberUpdatedState(receiverReady)
        val selectedCurrent by rememberUpdatedState(selected)
        val receiverReadyCurrent by rememberUpdatedState(receiverReady)

        val mediaPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            mediaPermissionMissing = !hasUsableMediaPermission(context)
            if (!mediaPermissionMissing) { media = loadMedia(context); if (selected == null) selected = media.firstOrNull() }
        }
        val nearbyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            nearbyPermissionMissing = !hasNearbyPermissions(context)
            if (!nearbyPermissionMissing) transfer.start()
        }

        val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraMissing = !granted
            if (granted) startHiddenAI(scope, { g -> handleGesture(g, selectedCurrent, receiverReadyCurrent, { receiverReady = it }, { phaseText = it }, { busy = it }, { progress = it }, { gestureOverlay = it }, statusSetter = { status = it }) })
        }

        fun refreshMedia() {
            media = loadMedia(context)
            if (selected == null) selected = media.firstOrNull()
        }

        LaunchedEffect(Unit) {
            transfer.onState = { value -> scope.launch(Dispatchers.Main.immediate) { status = value } }
            transfer.onDevices = { value -> scope.launch(Dispatchers.Main.immediate) { devices = value } }
            transfer.onIncomingReady = { scope.launch(Dispatchers.Main.immediate) { phase = AirPhase.IDLE; receiverReady = true; phaseText = "Incoming drop detected — close your hand, then open it to receive" } }
            transfer.onProgress = { value -> scope.launch(Dispatchers.Main.immediate) { progress = value } }
            transfer.onReceived = { name -> scope.launch(Dispatchers.Main.immediate) { receiverReady = false; busy = false; progress = 100; status = "Received $name — saved to Gallery"; phaseText = "AI hand detection is ready"; gestureOverlay = GestureOverlayEvent(GestureOverlayKind.RECEIVED, null) } }
        transfer.onTransferFinished = { success -> scope.launch(Dispatchers.Main.immediate) { busy = false; if (success) { progress = 100; status = "Drop complete — file sent"; phaseText = "AI hand detection is ready" } else { status = "Transfer failed — try the air drop again"; phaseText = "AI hand detection is ready" } } }
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
            } else refreshMedia()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraMissing = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else startHiddenAI(scope, { g -> handleGesture(g, selectedCurrent, receiverReadyCurrent, { receiverReady = it }, { phaseText = it }, { busy = it }, { progress = it }, { gestureOverlay = it }, statusSetter = { status = it }) })
        }

        MaterialTheme(colorScheme = DropShareColors) {
            Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                CenterAlignedTopAppBar(
                    title = { Row(verticalAlignment = Alignment.CenterVertically) { DropShareMark(32.dp); Spacer(Modifier.width(9.dp)); Text("DropShare", fontWeight = FontWeight.Bold) } },
                    actions = { TextButton(onClick = { how = true }) { Text("How") } }
                )
            }) { pad ->
                Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(8.dp))
                    HeroCard(devices.isNotEmpty(), receiverReady, phaseText)
                    Spacer(Modifier.height(12.dp))
                    StatusCard(status, devices.size, { requestQuickSettingsTile() })
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Your media", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { refreshMedia() }) { Text("Refresh") }
                    }
                    Text("Tap a photo or video to hold it. Then use the air grab and drop gesture.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    if (mediaPermissionMissing) {
                        PermissionCard("Allow Photos and Videos so DropShare can display your media.") { mediaPermissionLauncher.launch(mediaPermissionsForCurrentApi().toTypedArray()) }
                    } else if (media.isEmpty()) {
                        EmptyMediaCard()
                    } else {
                        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().height(360.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), userScrollEnabled = true) {
                            items(media, key = { it.uri.toString() }) { item -> MediaTile(item, selected?.uri == item.uri) { selected = item } }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    selected?.let { SelectedCard(it) }
                    Spacer(Modifier.height(14.dp))
                    if (nearbyPermissionMissing) {
                        PermissionCard("Nearby-device access is needed to discover and transfer to another DropShare phone.") { nearbyPermissionLauncher.launch(nearbyPermissionsForCurrentApi().toTypedArray()) }
                    }
                    if (cameraMissing) PermissionCard("Camera access is needed for the invisible AI hand-recognition layer.") { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                    Spacer(Modifier.height(8.dp))
                    Text("Nearby phones", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (devices.isEmpty()) NearbyEmptyState() else devices.forEach { (_, name) -> NearbyDeviceCard(name.removePrefix("DropShare-")) }
                    if (progress > 0) { Spacer(Modifier.height(14.dp)); TransferProgressCard(progress, progress < 100) }
                    Spacer(Modifier.height(30.dp))
                }
            }
            GestureFeedbackOverlay(event = gestureOverlay) { gestureOverlay = null }
            if (how) HowItWorksDialog { how = false }
        }
    }

    @Composable
    private fun GestureFeedbackOverlay(event: GestureOverlayEvent?, onFinished: () -> Unit) {
        val visible = event != null
        val infinite = rememberInfiniteTransition(label = "gesture-overlay")
        val pulse by infinite.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(650, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "gesture-pulse"
        )
        val dropPulse by infinite.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(420, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "drop-pulse"
        )
        LaunchedEffect(event?.id) {
            if (event != null) {
                if (event.kind == GestureOverlayKind.CARRYING) return@LaunchedEffect
                kotlinx.coroutines.delay(1150)
                onFinished()
            }
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.82f, animationSpec = tween(260)),
            exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.88f, animationSpec = tween(220))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 28.dp)
                        .widthIn(max = 330.dp),
                    shape = RoundedCornerShape(30.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val current = event
                        if (current?.item != null) {
                            var bitmap by remember(current.item.uri) { mutableStateOf<Bitmap?>(null) }
                            LaunchedEffect(current.item.uri) {
                                bitmap = withContext(Dispatchers.IO) { loadThumbnail(this@MainActivity, current.item.uri) }
                            }
                            Box(
                                modifier = Modifier
                                    .size(104.dp)
                                    .graphicsLayer {
                                        val animatedScale = when (current.kind) {
                                            GestureOverlayKind.CARRYING -> pulse
                                            GestureOverlayKind.DROPPED -> dropPulse
                                            GestureOverlayKind.RECEIVED -> pulse
                                        }
                                        scaleX = animatedScale
                                        scaleY = animatedScale
                                        translationY = if (current.kind == GestureOverlayKind.DROPPED) (1f - dropPulse) * 45f else 0f
                                    }
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                if (current.kind == GestureOverlayKind.CARRYING || current.kind == GestureOverlayKind.DROPPED) {
                                    Surface(
                                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp),
                                        shape = RoundedCornerShape(50),
                                        color = if (current.kind == GestureOverlayKind.DROPPED) MaterialTheme.colorScheme.secondary.copy(alpha = 0.94f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
                                    ) { Text(if (current.kind == GestureOverlayKind.DROPPED) "DROPPING" else "HELD", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(104.dp)
                                    .graphicsLayer { scaleX = pulse; scaleY = pulse }
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    when (event?.kind) {
                                        GestureOverlayKind.RECEIVED -> "✓"
                                        GestureOverlayKind.DROPPED -> "↓"
                                        GestureOverlayKind.CARRYING -> "✋"
                                        null -> ""
                                    },
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            when (event?.kind) {
                                GestureOverlayKind.CARRYING -> "IMAGE CARRIED"
                                GestureOverlayKind.DROPPED -> "IMAGE DROPPED"
                                GestureOverlayKind.RECEIVED -> "DROP RECEIVED"
                                null -> ""
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when (event?.kind) {
                                GestureOverlayKind.CARRYING -> "Keep your hand closed and move toward the other phone."
                                GestureOverlayKind.DROPPED -> "Your open-palm drop was detected. Sending the media now."
                                GestureOverlayKind.RECEIVED -> "Open-palm drop accepted. The media is being saved to Gallery."
                                null -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (event?.kind == GestureOverlayKind.CARRYING) {
                            Spacer(Modifier.height(14.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            TileService.requestAddTileService(
                this,
                ComponentName(this, DropShareTileService::class.java),
                getString(R.string.quick_settings_tile_label),
                Icon.createWithResource(this, R.drawable.ic_qs_dropshare),
                mainExecutor
            ) { }
        } else {
            Toast.makeText(this, "Swipe down twice, tap Edit, then add DropShare.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startHiddenAI(scope: kotlinx.coroutines.CoroutineScope, callback: (AirGesture) -> Unit) {
        bindHiddenCamera { gesture -> scope.launch(Dispatchers.Main.immediate) { callback(gesture) } }
    }

    private fun handleGesture(
        gesture: AirGesture,
        selected: MediaItem?,
        receiverReady: Boolean,
        setReceiver: (Boolean) -> Unit,
        setPhaseText: (String) -> Unit,
        setBusy: (Boolean) -> Unit,
        setProgress: (Int) -> Unit,
        setGestureOverlay: (GestureOverlayEvent) -> Unit,
        statusSetter: (String) -> Unit
    ) {
        if (receiverReady) {
            if (gesture == AirGesture.FIST) {
                phase = AirPhase.READY
                setGestureOverlay(GestureOverlayEvent(GestureOverlayKind.CARRYING, null))
                setPhaseText("Fist detected — open your hand to DROP and accept")
            } else if (gesture == AirGesture.OPEN_PALM && phase == AirPhase.READY && transfer.hasPendingIncoming()) {
                phase = AirPhase.IDLE
                setReceiver(false)
                transfer.acceptIncomingByGesture()
                setGestureOverlay(GestureOverlayEvent(GestureOverlayKind.RECEIVED, null))
                setBusy(true)
                setPhaseText("Drop accepted — receiving…")
            }
            return
        }
        val item = selected ?: return
        if (gesture == AirGesture.FIST && phase != AirPhase.GRABBED) {
            phase = AirPhase.GRABBED
            transfer.prepareSender(item.uri)
            transfer.markCarrying()
            transfer.requestTargetConnection()
            setGestureOverlay(GestureOverlayEvent(GestureOverlayKind.CARRYING, item))
            setPhaseText("Grabbed ${item.name} — move toward the other phone")
            statusSetter("Media grabbed — connecting to the nearest DropShare phone…")
        } else if (gesture == AirGesture.OPEN_PALM && phase == AirPhase.GRABBED) {
            phase = AirPhase.IDLE
            transfer.markReleased()
            setGestureOverlay(GestureOverlayEvent(GestureOverlayKind.DROPPED, item))
            setBusy(true)
            if (!transfer.hasConnection()) {
                setPhaseText("Dropped — waiting for the nearby phone to connect…")
                statusSetter("Drop detected — connecting to the receiver…")
            } else {
                setPhaseText("Dropped — transferring…")
                kotlinx.coroutines.MainScope().launch {
                    val queued = transfer.dropSelectedFile()
                    if (!queued) {
                        setBusy(false)
                        statusSetter("Drop could not be started — try again")
                        setPhaseText("AI hand detection is ready")
                    }
                }
            }
        }
    }

    @Composable private fun HeroCard(connected: Boolean, receiverReady: Boolean, phaseText: String) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary) { DropShareMark(54.dp, Modifier.padding(10.dp), Color.White) }
                    Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) {
                        Text("Grab it. Move it. Drop it.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text(if (receiverReady) "A nearby phone is waiting for your receiving gesture." else "Your camera and on-device AI quietly watch for the hand gesture.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.height(14.dp)); Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(connected); Spacer(Modifier.width(8.dp)); Text(if (connected) "Nearby DropShare phone detected" else "Searching for nearby DropShare phones") }
                Spacer(Modifier.height(8.dp)); Text(phaseText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }

    @Composable private fun StatusCard(status: String, deviceCount: Int, onAddTile: () -> Unit) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(true); Spacer(Modifier.width(10.dp));
                    Column(Modifier.weight(1f)) {
                        Text(status, fontWeight = FontWeight.SemiBold)
                        Text("$deviceCount nearby phone${if (deviceCount == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onAddTile, contentPadding = PaddingValues(0.dp)) {
                    Text("Add DropShare to Quick Settings")
                }
            }
        }
    }

    @Composable private fun MediaTile(item: MediaItem, selected: Boolean, onClick: () -> Unit) {
        var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(item.uri) { bitmap = withContext(Dispatchers.IO) { loadThumbnail(this@MainActivity, item.uri) } }
        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (item.isVideo) Surface(Modifier.align(Alignment.Center), shape = CircleShape, color = Color(0xCC000000)) { Text("▶", color = Color.White, modifier = Modifier.padding(10.dp)) }
            if (selected) Box(Modifier.fillMaxSize().background(Color(0x333F51FF)))
        }
    }

    @Composable private fun SelectedCard(item: MediaItem) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Text("✦", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("Held media", fontWeight = FontWeight.Bold); Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }; Text("✋ AI", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) } } }

    @Composable private fun PermissionCard(message: String, onClick: () -> Unit) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text(message); Spacer(Modifier.height(8.dp)); Button(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Text("Allow access") } } } }
    @Composable private fun EmptyMediaCard() { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("No photos or videos found", fontWeight = FontWeight.Bold); Text("Add media to this phone and tap Refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) } } }
    @Composable private fun NearbyEmptyState() { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Text("Keep DropShare open on the other phone and move closer.", Modifier.padding(18.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    @Composable private fun NearbyDeviceCard(name: String) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) { DropShareMark(38.dp, Modifier.padding(8.dp), MaterialTheme.colorScheme.primary) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(name.ifBlank { "Nearby DropShare" }, fontWeight = FontWeight.Bold); Text("Ready for air drop", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; StatusDot(true) } } }
    @Composable private fun TransferProgressCard(progress: Int, active: Boolean) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Row { Text(if (active) "Transferring…" else "Transfer complete", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("$progress%", fontWeight = FontWeight.Bold) }; Spacer(Modifier.height(9.dp)); LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth()) } } }
    @Composable private fun StatusDot(online: Boolean) { Surface(shape = CircleShape, color = if (online) Color(0xFF35C759) else MaterialTheme.colorScheme.outlineVariant) { Spacer(Modifier.size(9.dp)) } }
    @Composable private fun DropShareMark(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onPrimary) { Box(modifier.size(size), contentAlignment = Alignment.Center) { Text("↗", color = tint, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge) } }
    @Composable private fun HowItWorksDialog(onDismiss: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, title = { Text("Air Grab & Drop") }, text = { Text("DropShare automatically displays your photos and videos. The front camera runs invisibly while DropShare is open and an on-device AI gesture recognizer watches for a closed-fist grab and open-palm drop. On the receiving phone, the same drop gesture accepts the incoming transfer. There is no Accept or Decline popup.") }, confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }) }
}

private fun hasNearbyPermissions(context: Context): Boolean = nearbyPermissionsForCurrentApi().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun nearbyPermissionsForCurrentApi(): List<String> = when {
    Build.VERSION.SDK_INT >= 32 -> listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.NEARBY_WIFI_DEVICES
    )
    Build.VERSION.SDK_INT >= 31 -> listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
    )
    Build.VERSION.SDK_INT >= 29 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
}

private fun hasUsableMediaPermission(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= 34 -> {
        val images = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        val videos = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        val selected = ContextCompat.checkSelfPermission(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED
        (images && videos) || selected
    }
    Build.VERSION.SDK_INT >= 33 -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    else -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

private fun mediaPermissionsForCurrentApi(): List<String> = when {
    Build.VERSION.SDK_INT >= 34 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

private fun loadMedia(context: Context): List<MediaItem> {
    val resolver = context.contentResolver
    val result = mutableListOf<MediaItem>()
    val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val videos = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED)
    fun query(collection: Uri, isVideo: Boolean) {
        resolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext() && result.size < 120) {
                val id = c.getLong(idCol)
                val uri = Uri.withAppendedPath(collection, id.toString())
                result += MediaItem(uri, c.getString(nameCol) ?: "DropShare media", c.getString(mimeCol) ?: if (isVideo) "video/*" else "image/*", isVideo, c.getLong(dateCol))
            }
        }
    }
    query(images, false); query(videos, true)
    return result.sortedByDescending { it.dateAdded }
}

private fun loadThumbnail(context: Context, uri: Uri): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 29) {
        context.contentResolver.loadThumbnail(uri, android.util.Size(420, 420), null)
    } else {
        val mime = context.contentResolver.getType(uri).orEmpty()
        if (mime.startsWith("video/")) {
            android.provider.MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                android.content.ContentUris.parseId(uri),
                android.provider.MediaStore.Video.Thumbnails.MINI_KIND,
                null
            )
        } else {
            android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }
} catch (_: Exception) { null }

private fun GestureRecognizerResult.topGesture(): AirGesture { val c: Category? = gestures().firstOrNull()?.maxByOrNull { it.score() }; return when (c?.categoryName()) { "Open_Palm" -> AirGesture.OPEN_PALM; "Closed_Fist" -> AirGesture.FIST; else -> AirGesture.NONE } }

private class DropTransfer(private val context: Context) {
    private val client = Nearby.getConnectionsClient(context)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val serviceId = context.packageName
    private val localEndpointName = "DropShare-${UUID.randomUUID()}"
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val endpoints = linkedMapOf<String, String>()
    private val outgoingPackets = mutableMapOf<Long, Pair<File, FileInputStream>>()
    private var connectedEndpoint: String? = null
    private var pendingIncoming: String? = null
    private var preparedUri: Uri? = null
    private var carrying = false
    private var releaseRequested = false
    private var accepting = false
    private var started = false

    var onState: (String) -> Unit = {}
    var onDevices: (List<Pair<String, String>>) -> Unit = {}
    var onIncomingReady: () -> Unit = {}
    var onProgress: (Int) -> Unit = {}
    var onReceived: (String) -> Unit = {}
    var onTransferFinished: (Boolean) -> Unit = {}

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            endpoints[id] = info.endpointName
            onDevices(endpoints.map { it.key to it.value })
            onState("Nearby phone found — make a fist to grab")
        }

        override fun onEndpointLost(id: String) {
            endpoints.remove(id)
            if (connectedEndpoint == id) connectedEndpoint = null
            if (pendingIncoming == id) pendingIncoming = null
            onDevices(endpoints.map { it.key to it.value })
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            if (info.isIncomingConnection) {
                pendingIncoming = id
                onState("Incoming air drop — perform fist then open palm to accept")
                onIncomingReady()
            } else {
                client.acceptConnection(id, payloadCallback)
                    .addOnFailureListener { onState("Nearby connection failed — try the drop again") }
            }
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoint = id
                if (preparedUri != null && carrying && releaseRequested) {
                    onState("Drop detected — transferring…")
                    scope.launch {
                        if (!dropSelectedFile()) {
                            onTransferFinished(false)
                            onState("Transfer could not be started — try the drop again")
                        }
                    }
                } else {
                    onState("Connected — move toward the other phone and open your hand")
                }
            } else {
                if (pendingIncoming == id) pendingIncoming = null
                if (connectedEndpoint == id) connectedEndpoint = null
                onState("Connection cancelled — try the gesture again")
            }
        }

        override fun onDisconnected(id: String) {
            if (connectedEndpoint == id) connectedEndpoint = null
            if (pendingIncoming == id) pendingIncoming = null
            accepting = false
            onState("Searching for nearby phones…")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            if (payload.type != Payload.Type.STREAM) return
            val stream = payload.asStream() ?: return
            scope.launch {
                try {
                    DataInputStream(BufferedInputStream(stream.asInputStream())).use { input ->
                        val headerLength = input.readInt()
                        require(headerLength in 1..32768)
                        val header = ByteArray(headerLength)
                        input.readFully(header)
                        val parts = String(header, Charsets.UTF_8).split("|", limit = 4)
                        require(parts.size == 4 && parts[0] == "DS2")
                        val expected = parts[3].toLong()
                        require(expected > 0)
                        saveStreamToGallery(
                            input,
                            URLDecoder.decode(parts[2], "UTF-8"),
                            parts[1],
                            expected
                        )
                    }
                } catch (_: Exception) {
                    onState("Receiving failed — try the air drop again")
                } finally {
                    payload.close()
                }
            }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
            if (update.totalBytes > 0) {
                onProgress((update.bytesTransferred * 100 / update.totalBytes).toInt().coerceIn(0, 100))
            }
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    if (cleanupOutgoing(update.payloadId)) {
                        onProgress(100)
                        onTransferFinished(true)
                    }
                }
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    if (cleanupOutgoing(update.payloadId)) onTransferFinished(false)
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        onState("Searching for nearby phones…")
        client.startAdvertising(
            localEndpointName,
            serviceId,
            lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener {
            onState("Nearby advertising could not start")
        }
        client.startDiscovery(
            serviceId,
            discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener {
            onState("Nearby discovery could not start")
        }
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        outgoingPackets.values.forEach { (_, input) -> runCatching { input.close() } }
        outgoingPackets.values.forEach { (file, _) -> file.delete() }
        outgoingPackets.clear()
        scope.cancel()
        started = false
    }

    fun prepareSender(uri: Uri) {
        preparedUri = uri
        carrying = false
        releaseRequested = false
    }

    fun markCarrying() {
        carrying = true
        releaseRequested = false
    }

    fun markReleased() {
        releaseRequested = true
    }

    fun requestTargetConnection() {
        if (connectedEndpoint != null || pendingIncoming != null) return
        val target = endpoints.keys.firstOrNull() ?: run {
            onState("No nearby phone found — move closer")
            return
        }
        client.requestConnection(localEndpointName, target, lifecycle)
            .addOnFailureListener { onState("Could not reach the nearby phone") }
    }

    fun hasPendingIncoming() = pendingIncoming != null
    fun hasConnection() = connectedEndpoint != null

    fun acceptIncomingByGesture() {
        val id = pendingIncoming ?: return
        if (accepting) return
        accepting = true
        onState("Drop accepted — receiving…")
        client.acceptConnection(id, payloadCallback)
            .addOnFailureListener {
                accepting = false
                onState("Could not accept the drop — try again")
            }
    }

    suspend fun dropSelectedFile(): Boolean = withContext(Dispatchers.IO) {
        val id = connectedEndpoint ?: return@withContext false
        val uri = preparedUri ?: return@withContext false
        val mime = context.contentResolver.getType(uri) ?: return@withContext false
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) return@withContext false

        val name = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { if (it.moveToFirst()) it.getString(0) else "DropShare-media" } ?: "DropShare-media"

        val source = File.createTempFile("dropshare_", ".bin", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(source).use { output -> input.copyTo(output) }
            } ?: return@withContext false

            val size = source.length()
            if (size <= 0) return@withContext false

            val header = "DS2|$mime|${URLEncoder.encode(name, "UTF-8")}|$size"
                .toByteArray(Charsets.UTF_8)
            val packet = File.createTempFile("dropshare_packet_", ".bin", context.cacheDir)

            try {
                DataOutputStream(FileOutputStream(packet)).use { output ->
                    output.writeInt(header.size)
                    output.write(header)
                    FileInputStream(source).use { input -> input.copyTo(output) }
                }

                val input = FileInputStream(packet)
                val payload = Payload.fromStream(input)
                synchronized(outgoingPackets) {
                    outgoingPackets[payload.id] = packet to input
                }

                val accepted = client.sendPayload(id, payload).awaitSuccess()
                if (!accepted) {
                    cleanupOutgoing(payload.id)
                    return@withContext false
                }
                true
            } catch (_: Exception) {
                packet.delete()
                false
            }
        } finally {
            source.delete()
            preparedUri = null
            carrying = false
            releaseRequested = false
        }
    }

    private fun cleanupOutgoing(payloadId: Long): Boolean {
        val pair = synchronized(outgoingPackets) { outgoingPackets.remove(payloadId) } ?: return false
        runCatching { pair.second.close() }
        pair.first.delete()
        return true
    }

    private fun saveStreamToGallery(
        input: DataInputStream,
        name: String,
        mime: String,
        expected: Long
    ) {
        val resolver = context.contentResolver
        val video = mime.startsWith("video/")
        val collection = if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name.ifBlank { "DropShare-media" })
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/DropShare" else "Pictures/DropShare")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Cannot create gallery item")
        try {
            var written = 0L
            resolver.openOutputStream(uri)?.use { output ->
                val buffer = ByteArray(64 * 1024)
                while (written < expected) {
                    val want = minOf(buffer.size.toLong(), expected - written).toInt()
                    val read = input.read(buffer, 0, want)
                    require(read > 0)
                    output.write(buffer, 0, read)
                    written += read
                    onProgress((written * 100 / expected).toInt().coerceIn(0, 99))
                }
            } ?: throw IllegalStateException("Cannot open gallery output")
            require(written == expected)
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            onProgress(100)
            onReceived(name)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
private suspend fun com.google.android.gms.tasks.Task<Void>.awaitSuccess(): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont -> addOnSuccessListener { if (cont.isActive) cont.resume(true) {} }; addOnFailureListener { if (cont.isActive) cont.resume(false) {} } }
