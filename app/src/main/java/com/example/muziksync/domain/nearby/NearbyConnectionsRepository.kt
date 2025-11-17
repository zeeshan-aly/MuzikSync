package com.example.muziksync.domain.nearby

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface NearbyConnectionsRepository {
    // Host methods
    fun startAdvertising(hostName: String): Flow<GuestConnectionEvent>
    suspend fun stopAdvertising()
    suspend fun sendFile(endpointIds: List<String>, fileUri: Uri)
    suspend fun sendCommand(endpointIds: List<String>, command: String)

    // Guest methods
    fun startDiscovery(): Flow<HostDiscoveredEvent>
    suspend fun stopDiscovery()
    suspend fun connectToHost(endpointId: String)

    // Guest event flows
    val fileReceivedFlow: Flow<Uri>
    val commandReceivedFlow: Flow<String>
    val fileTransferInProgressFlow: Flow<Unit>
}

data class GuestConnectionEvent(
    val endpointId: String,
    val guestName: String?,
    val type: Type
) {
    enum class Type { Connected, Disconnected }
}

data class HostDiscoveredEvent(
    val endpointId: String,
    val hostName: String
) 