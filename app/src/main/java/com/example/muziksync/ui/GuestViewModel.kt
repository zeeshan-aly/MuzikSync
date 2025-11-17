package com.example.muziksync.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.muziksync.domain.nearby.NearbyConnectionsRepository
import com.example.muziksync.domain.nearby.HostDiscoveredEvent
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import android.net.Uri
import kotlinx.coroutines.flow.collectLatest
import android.util.Log

enum class GuestDiscoveryStatus { Idle, Discovering, HostFound, Connected }
enum class GuestFileTransferStatus { NotStarted, Receiving, Received, Error }
enum class GuestPlaybackStatus { Idle, Ready, Playing, Paused, Error }

data class GuestUiState(
    val discoveryStatus: GuestDiscoveryStatus = GuestDiscoveryStatus.Idle,
    val hostName: String? = null,
    val connectionStatus: String = "Not connected",
    val fileTransferStatus: GuestFileTransferStatus = GuestFileTransferStatus.NotStarted,
    val playbackStatus: GuestPlaybackStatus = GuestPlaybackStatus.Idle,
    val playbackPosition: Long = 0L,
    val discoveredHosts: List<HostDiscoveredEvent> = emptyList(),
    val receivedFileUri: Uri? = null
)

class GuestViewModel(
    private val repository: NearbyConnectionsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(GuestUiState())
    val uiState: StateFlow<GuestUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.fileTransferInProgressFlow.collectLatest {
                onFileTransferInProgress()
            }
        }
        viewModelScope.launch {
            repository.fileReceivedFlow.collectLatest { uri ->
                Log.d("GuestViewModel", "Received file Uri: $uri")
                _uiState.value = _uiState.value.copy(
                    fileTransferStatus = GuestFileTransferStatus.Received,
                    receivedFileUri = uri
                )
            }
        }
        viewModelScope.launch {
            repository.commandReceivedFlow.collectLatest { command ->
                if (command.startsWith("START_PLAYBACK:")) {
                    val timestamp = command.removePrefix("START_PLAYBACK:").toLongOrNull() ?: 0L
                    _uiState.value = _uiState.value.copy(
                        playbackStatus = GuestPlaybackStatus.Playing,
                        playbackPosition = timestamp
                    )
                }
                // Handle other commands (pause, seek, etc.) as needed
            }
        }
    }

    fun startDiscovery() {
        _uiState.value = _uiState.value.copy(discoveryStatus = GuestDiscoveryStatus.Discovering)
        viewModelScope.launch {
            repository.startDiscovery().collect { event ->
                _uiState.value = _uiState.value.copy(
                    discoveredHosts = _uiState.value.discoveredHosts + event,
                    discoveryStatus = GuestDiscoveryStatus.HostFound
                )
            }
        }
    }

    fun connectToHost(endpointId: String, hostName: String) {
        _uiState.value = _uiState.value.copy(
            discoveryStatus = GuestDiscoveryStatus.Connected,
            hostName = hostName,
            connectionStatus = "Connected to $hostName",
            discoveredHosts = emptyList()
        )
        viewModelScope.launch {
            repository.connectToHost(endpointId)
            _uiState.value = _uiState.value.copy(connectionStatus = "Connected to $hostName")
        }
    }

    fun onFileTransferInProgress() {
        _uiState.value = _uiState.value.copy(fileTransferStatus = GuestFileTransferStatus.Receiving)
    }

    fun onFileReceived() {
        _uiState.value = _uiState.value.copy(fileTransferStatus = GuestFileTransferStatus.Received)
    }

    fun onPlaybackCommand(command: String) {
        // TODO: Handle playback command
    }
}

class GuestViewModelFactory(private val repository: NearbyConnectionsRepository) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GuestViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GuestViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
} 