package com.dropshare.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private data class MediaItem(
    val uri: Uri,
    val name: String,
    val mime: String,
    val isVideo: Boolean,
    val dateAdded: Long
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transfer = DropTransfer(this)
        setContent {
            DropShareApp()
        }
    }

    override fun onDestroy() {
        transfer.stop()
        super.onDestroy()
    }

    @Composable
    private fun DropShareApp() {
        val context = LocalContext.current
        val mediaPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            val usable = hasUsableMediaPermission(context)
            if (usable) {
                refreshMedia()
            }
        }

        val nearbyPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            val okay = hasNearbyPermissions(context)
            if (okay) {
                transfer.start()
            }
        }

        var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
        var selected by remember { mutableStateOf<MediaItem?>(null) }
        var status by remember { mutableStateOf("Starting DropShare…") }
        var nearbyPermissionMissing by remember { mutableStateOf(false) }
        var mediaPermissionMissing by remember { mutableStateOf(false) }

        fun refreshMedia() {
            media = loadMedia(context)
            if (selected == null && media.isNotEmpty()) {
                selected = media.first()
            }
        }

        LaunchedEffect(Unit) {
            transfer.onState = { value ->
                status = value
            }

            transfer.onDevices = {}
            transfer.onIncomingReady = {}
            transfer.onProgress = {}
            transfer.onReceived = {}
            transfer.onTransferFinished = {}

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
                mediaPermissionMissing = false
                refreshMedia()
            }
        }

        Scaffold { padding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DropShare",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        if (nearbyPermissionMissing || mediaPermissionMissing) {
                            TextButton(
                                onClick = {
                                    if (nearbyPermissionMissing) {
                                        nearbyPermissionLauncher.launch(nearbyPermissionsForCurrentApi().toTypedArray())
                                    }
                                    if (mediaPermissionMissing) {
                                        mediaPermissionLauncher.launch(mediaPermissionsForCurrentApi().toTypedArray())
                                    }
                                }
                            ) {
                                Text("Grant permissions")
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = status,
                            modifier = Modifier.padding(16.dp),
                            textAlign = TextAlign.Start
                        )
                    }

                    if (media.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (mediaPermissionMissing) {
                                    "Waiting for media permission"
                                } else {
                                    "No media found"
                                }
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(media) { item ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selected = item
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected == item) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Text(
                                            text = item.name,
                                            maxLines = 2
                                        )
                                        Spacer(modifier = Modifier.size(8.dp))
                                        Text(
                                            text = if (item.isVideo) "Video" else "Image",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (selected != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Text(
                                text = "Selected: ${selected!!.name}",
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (selected != null) {
                                Toast.makeText(
                                    context,
                                    "Selected: ${selected!!.name}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(context, "No item selected", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Use selected item")
                    }
                }
            }
        }
    }

    private fun nearbyPermissionsForCurrentApi(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    private fun hasNearbyPermissions(context: Context): Boolean =
        nearbyPermissionsForCurrentApi().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun mediaPermissionsForCurrentApi(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }

    private fun hasUsableMediaPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val images = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED

            val videos = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED

            images || videos
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun loadMedia(context: Context): List<MediaItem> {
        val resolver = context.contentResolver
        val result = mutableListOf<MediaItem>()

        fun loadCollection(collection: Uri, isVideo: Boolean) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_ADDED
            )

            resolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = cursor.getString(nameColumn) ?: "Untitled"
                    val mime = cursor.getString(mimeColumn) ?: "application/octet-stream"
                    val dateAdded = cursor.getLong(dateAddedColumn)

                    result += MediaItem(
                        uri = uri,
                        name = name,
                        mime = mime,
                        isVideo = isVideo,
                        dateAdded = dateAdded
                    )
                }
            }
        }

        loadCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
        loadCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)

        return result.sortedByDescending { it.dateAdded }
    }
}
