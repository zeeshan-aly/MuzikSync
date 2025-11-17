package com.example.muziksync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.muziksync.ui.theme.MUZIKSYNCTheme
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.muziksync.ui.HostViewModel
import com.example.muziksync.ui.HostUiState
import com.example.muziksync.ui.GuestViewModel
import com.example.muziksync.ui.GuestDiscoveryStatus
import com.example.muziksync.ui.GuestFileTransferStatus
import com.example.muziksync.ui.GuestPlaybackStatus
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import android.app.Application
import com.example.muziksync.ui.HostViewModelFactory
import com.example.muziksync.data.nearby.NearbyConnectionsManager
import com.example.muziksync.domain.nearby.NearbyConnectionsRepository
import android.Manifest
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import com.example.muziksync.ui.FileTransferStatus
import java.util.concurrent.TimeUnit
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import com.example.muziksync.ui.GuestViewModelFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.exoplayer2.ui.PlayerView
import android.util.Log

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MUZIKSYNCTheme {
                val navController = rememberNavController()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("home") { HomeScreen(navController) }
                        composable("host") { HostScreen() }
                        composable("guest") { GuestScreen() }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(navController: NavHostController) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = { navController.navigate("host") }) {
            Text("Host Mode")
        }
        Button(onClick = { navController.navigate("guest") }, modifier = Modifier.padding(top = 16.dp)) {
            Text("Guest Mode")
        }
    }
}

@Composable
fun HostScreen() {
    val context = LocalContext.current
    val repository: NearbyConnectionsRepository = remember { NearbyConnectionsManager(context) }
    val viewModel: HostViewModel = viewModel(factory = HostViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                val name = getFileNameFromUri(context, it)
                viewModel.onFileSelected(it, name ?: "Unknown.mp3")
            }
        }
    )
    val requiredPermissions = remember { getNearbyRequiredPermissions() }
    var permissionsGranted by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var showPermissionSnackbar by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { result ->
            permissionsGranted = requiredPermissions.all { result[it] == true }
            if (!permissionsGranted) {
                showPermissionSnackbar = true
            }
        }
    )
    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredPermissions)
    }
    if (showPermissionSnackbar) {
        LaunchedEffect(snackbarHostState) {
            snackbarHostState.showSnackbar("Nearby/Bluetooth/Location permissions are required.")
            showPermissionSnackbar = false
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SnackbarHost(hostState = snackbarHostState)
        // Advertising controls
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (permissionsGranted) {
                        if (uiState.isAdvertising) viewModel.stopAdvertising()
                        else viewModel.startAdvertising("HostDevice")
                    } else {
                        permissionLauncher.launch(requiredPermissions)
                    }
                },
                enabled = permissionsGranted
            ) {
                Text(if (uiState.isAdvertising) "Stop Advertising" else "Start Advertising")
            }
            Text(
                text = if (uiState.isAdvertising) "Advertising..." else "Not advertising",
                color = if (uiState.isAdvertising) androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
        if (!permissionsGranted) {
            Text("Nearby/Bluetooth/Location permissions are required to host.", color = androidx.compose.ui.graphics.Color.Red, modifier = Modifier.padding(top = 8.dp))
            Button(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Open App Settings")
            }
        }
        Button(onClick = {
            filePickerLauncher.launch(arrayOf("audio/mpeg", "audio/mp3"))
        }) {
            Text("Pick MP3 File")
        }
        if (uiState.selectedFileName != null) {
            Text("Selected: ${uiState.selectedFileName}", modifier = Modifier.padding(top = 16.dp))
        }

        // Guest List
        Text("Connected Guests:", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 24.dp))
        if (uiState.guests.isEmpty()) {
            Text("No guests connected.", modifier = Modifier.padding(top = 8.dp))
        } else {
            LazyColumn(modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).fillMaxSize(0.5f)) {
                items(uiState.guests) { guest ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(guest)
                        val status = uiState.fileTransferStatus[guest] ?: FileTransferStatus.NotSent
                        Text(
                            text = when (status) {
                                FileTransferStatus.NotSent -> "Not Sent"
                                FileTransferStatus.Sending -> "Sending..."
                                FileTransferStatus.Sent -> "Sent"
                                FileTransferStatus.Error -> "Error"
                            },
                            color = when (status) {
                                FileTransferStatus.Sent -> androidx.compose.ui.graphics.Color.Green
                                FileTransferStatus.Error -> androidx.compose.ui.graphics.Color.Red
                                FileTransferStatus.Sending -> androidx.compose.ui.graphics.Color.Blue
                                else -> androidx.compose.ui.graphics.Color.Gray
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
        // Send file to all guests
        Button(
            onClick = { viewModel.sendFileToAllGuests() },
            enabled = uiState.selectedFileUri != null && uiState.guests.isNotEmpty(),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Send File to All Guests")
        }
        // Send playback command
        Button(
            onClick = {
                // For demo, use current system time as the playback start timestamp
                val startTimestamp = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5) // 5 seconds from now
                viewModel.sendStartPlaybackCommand(startTimestamp)
            },
            enabled = uiState.guests.isNotEmpty(),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Send Start Playback Command")
        }

        // Playback Controls
        Text("Playback Controls:", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = { viewModel.seekTo((uiState.playbackPosition - 5000).coerceAtLeast(0L)) }) {
                Icon(Icons.Filled.FastRewind, contentDescription = "Rewind 5s")
            }
            IconButton(onClick = { if (uiState.isPlaying) viewModel.pause() else viewModel.play() }) {
                Icon(if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (uiState.isPlaying) "Pause" else "Play")
            }
            IconButton(onClick = { viewModel.seekTo(uiState.playbackPosition + 5000) }) {
                Icon(Icons.Filled.FastForward, contentDescription = "Forward 5s")
            }
        }
        Text("Playback Position: ${uiState.playbackPosition / 1000}s", modifier = Modifier.padding(top = 8.dp))
    }
}

fun getFileNameFromUri(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    return cursor?.use {
        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        it.moveToFirst()
        it.getString(nameIndex)
    }
}

fun getNearbyRequiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}

@Composable
fun GuestScreen() {
    val context = LocalContext.current
    val repository: NearbyConnectionsRepository = remember { NearbyConnectionsManager(context) }
    val viewModel: GuestViewModel = viewModel(factory = GuestViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }
    val currentFileUri by rememberUpdatedState(uiState.receivedFileUri)
    val currentPlaybackStatus by rememberUpdatedState(uiState.playbackStatus)
    val currentPlaybackPosition by rememberUpdatedState(uiState.playbackPosition)

    // Prepare and play when file and command received
    LaunchedEffect(currentFileUri, currentPlaybackStatus, currentPlaybackPosition) {
        if (currentFileUri != null && currentPlaybackStatus == GuestPlaybackStatus.Playing) {
            val mediaItem = MediaItem.fromUri(currentFileUri!!)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.seekTo(currentPlaybackPosition)
            exoPlayer.playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Guest Mode", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("Discovery Status: ${uiState.discoveryStatus}", modifier = Modifier.padding(top = 16.dp))
        Text("Connection Status: ${uiState.connectionStatus}", modifier = Modifier.padding(top = 8.dp))
        when (uiState.fileTransferStatus) {
            GuestFileTransferStatus.Receiving -> Text("Receiving file...", color = androidx.compose.ui.graphics.Color.Blue, modifier = Modifier.padding(top = 8.dp))
            GuestFileTransferStatus.Received -> Text("File received!", color = androidx.compose.ui.graphics.Color.Green, modifier = Modifier.padding(top = 8.dp))
            GuestFileTransferStatus.Error -> Text("File transfer failed.", color = androidx.compose.ui.graphics.Color.Red, modifier = Modifier.padding(top = 8.dp))
            else -> {}
        }
        Text("Playback Status: ${uiState.playbackStatus}", modifier = Modifier.padding(top = 8.dp))
        if (uiState.discoveryStatus != GuestDiscoveryStatus.Connected) {
            Button(onClick = { viewModel.startDiscovery() }, modifier = Modifier.padding(top = 24.dp)) {
                Text("Start Discovery")
            }
            if (uiState.discoveredHosts.isNotEmpty()) {
                Text("Discovered Hosts:", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp))
                LazyColumn(modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).fillMaxSize(0.5f)) {
                    items(uiState.discoveredHosts) { host ->
                        Button(onClick = { viewModel.connectToHost(host.endpointId, host.hostName) }, modifier = Modifier.padding(bottom = 8.dp)) {
                            Text("Connect to ${host.hostName}")
                        }
                    }
                }
            }
        }
        if (uiState.fileTransferStatus == GuestFileTransferStatus.Received && uiState.receivedFileUri != null) {
            Log.d("GuestScreen", "Showing ExoPlayer for Uri: ${uiState.receivedFileUri}")
            AndroidView(factory = {
                PlayerView(it).apply { player = exoPlayer }
            }, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
        }
    }
}