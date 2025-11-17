package com.example.muziksync.data.nearby

import android.content.Context
import android.net.Uri
import com.example.muziksync.domain.nearby.NearbyConnectionsRepository
import com.example.muziksync.domain.nearby.GuestConnectionEvent
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.example.muziksync.domain.nearby.HostDiscoveredEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import android.util.Log

class NearbyConnectionsManager(private val context: Context) : NearbyConnectionsRepository {
    private val TAG = "NearbyConnectionsMgr"
    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private val serviceId = "com.example.muziksync.SERVICE_ID"
    private val connectedGuests = mutableMapOf<String, String?>() // endpointId -> guestName

    private val _fileReceivedFlow = MutableSharedFlow<Uri>()
    override val fileReceivedFlow = _fileReceivedFlow.asSharedFlow()
    private val _commandReceivedFlow = MutableSharedFlow<String>()
    override val commandReceivedFlow = _commandReceivedFlow.asSharedFlow()
    private val incomingFilePayloads = mutableMapOf<Long, java.io.File>()
    private val _fileTransferInProgressFlow = MutableSharedFlow<Unit>()
    override val fileTransferInProgressFlow = _fileTransferInProgressFlow.asSharedFlow()

    override fun startAdvertising(hostName: String): Flow<GuestConnectionEvent> = callbackFlow {
        Log.d(TAG, "Host: Starting advertising as $hostName")
        // TODO: Ensure required permissions are granted before advertising
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: com.google.android.gms.nearby.connection.ConnectionInfo) {
                Log.d(TAG, "Host: Connection initiated from $endpointId")
                // Accept all connections for now
                connectionsClient.acceptConnection(endpointId, object : PayloadCallback() {
                    override fun onPayloadReceived(endpointId: String, payload: Payload) {}
                    override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
                })
            }
            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                Log.d(TAG, "Host: Connection result for $endpointId: ${result.status.statusCode}")
                if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                    // Connected
                    connectedGuests[endpointId] = null // Name will be set later if sent
                    trySend(GuestConnectionEvent(endpointId, null, GuestConnectionEvent.Type.Connected))
                } else {
                    // Connection rejected or error
                }
            }
            override fun onDisconnected(endpointId: String) {
                Log.d(TAG, "Host: Disconnected from $endpointId")
                connectedGuests.remove(endpointId)
                trySend(GuestConnectionEvent(endpointId, null, GuestConnectionEvent.Type.Disconnected))
            }
        }
        connectionsClient.startAdvertising(
            hostName,
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        )
        awaitClose {
            Log.d(TAG, "Host: Stopping advertising")
            connectionsClient.stopAdvertising()
            connectedGuests.clear()
        }
    }

    override suspend fun stopAdvertising() {
        Log.d(TAG, "Host: stopAdvertising() called")
        connectionsClient.stopAdvertising()
        connectedGuests.clear()
    }

    override suspend fun sendFile(endpointIds: List<String>, fileUri: Uri) {
        // TODO: Track file transfer status for each guest
        val filePayload = Payload.fromFile(context.contentResolver.openFileDescriptor(fileUri, "r")!!)
        endpointIds.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, filePayload)
        }
    }
    override suspend fun sendCommand(endpointIds: List<String>, command: String) {
        val commandPayload = Payload.fromBytes(command.toByteArray(Charsets.UTF_8))
        endpointIds.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, commandPayload)
        }
    }

    override fun startDiscovery(): Flow<HostDiscoveredEvent> = callbackFlow {
        Log.d(TAG, "Guest: Starting discovery")
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                Log.d(TAG, "Guest: Endpoint found $endpointId (${info.endpointName})")
                trySend(HostDiscoveredEvent(endpointId, info.endpointName))
            }
            override fun onEndpointLost(endpointId: String) {
                Log.d(TAG, "Guest: Endpoint lost $endpointId")
                // Optionally handle lost endpoints
            }
        }
        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            discoveryOptions
        )
        awaitClose {
            Log.d(TAG, "Guest: Stopping discovery")
            connectionsClient.stopDiscovery()
        }
    }

    override suspend fun stopDiscovery() {
        Log.d(TAG, "Guest: stopDiscovery() called")
        connectionsClient.stopDiscovery()
    }

    override suspend fun connectToHost(endpointId: String) {
        Log.d(TAG, "Guest: Requesting connection to $endpointId")
        connectionsClient.requestConnection(
            /* name= */ "GuestDevice",
            endpointId,
            object : ConnectionLifecycleCallback() {
                override fun onConnectionInitiated(endpointId: String, info: com.google.android.gms.nearby.connection.ConnectionInfo) {
                    Log.d(TAG, "Guest: Connection initiated with $endpointId")
                    connectionsClient.acceptConnection(endpointId, object : PayloadCallback() {
                        override fun onPayloadReceived(endpointId: String, payload: Payload) {
                            when (payload.type) {
                                Payload.Type.BYTES -> {
                                    val command = payload.asBytes()?.toString(Charsets.UTF_8)
                                    if (command != null) {
                                        _commandReceivedFlow.tryEmit(command)
                                    }
                                }
                                Payload.Type.FILE -> {
                                    val file = payload.asFile()?.asJavaFile()
                                    if (file != null) {
                                        incomingFilePayloads[payload.id] = file
                                    }
                                }
                                else -> {}
                            }
                        }
                        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
                            Log.d(TAG, "Guest: Payload transfer update from $endpointId: id=${update.payloadId}, status=${update.status}, bytesTransferred=${update.bytesTransferred}, totalBytes=${update.totalBytes}")
                            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) {
                                _fileTransferInProgressFlow.tryEmit(Unit)
                            }
                            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                                val file = incomingFilePayloads[update.payloadId]
                                if (file != null) {
                                    // Copy to app cache directory
                                    val cacheFile = java.io.File(context.cacheDir, "received_audio.mp3")
                                    file.copyTo(cacheFile, overwrite = true)
                                    val uri = Uri.fromFile(cacheFile)
                                    Log.d(TAG, "Emitting file received Uri: $uri (copied to cache)")
                                    _fileReceivedFlow.tryEmit(uri)
                                    incomingFilePayloads.remove(update.payloadId)
                                }
                            }
                        }
                    })
                }
                override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                    Log.d(TAG, "Guest: Connection result for $endpointId: ${result.status.statusCode}")
                    // Optionally handle connection result
                }
                override fun onDisconnected(endpointId: String) {
                    Log.d(TAG, "Guest: Disconnected from $endpointId")
                    // Optionally handle disconnect
                }
            }
        )
    }
} 