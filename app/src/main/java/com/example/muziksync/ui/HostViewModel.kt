package com.example.muziksync.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.muziksync.domain.nearby.GuestConnectionEvent
import kotlinx.coroutines.CoroutineScope
 import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.muziksync.domain.nearby.NearbyConnectionsRepository
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FileTransferStatus { NotSent, Sending, Sent, Error }

// UI state for the Host screen
data class HostUiState(
    val selectedFileUri: Uri? = null,
    val selectedFileName: String? = null,
    val guests: List<String> = emptyList(),
    val isPlaying: Boolean = false,
    val playbackPosition: Long = 0L,
    val isAdvertising: Boolean = false,
    val fileTransferStatus: Map<String, FileTransferStatus> = emptyMap()
)

class HostViewModel(
    private val repository: NearbyConnectionsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(HostUiState())
    val uiState: StateFlow<HostUiState> = _uiState.asStateFlow()

    private var advertisingJob: Job? = null

    fun startAdvertising(hostName: String) {
        _uiState.value = _uiState.value.copy(isAdvertising = true)
        advertisingJob = viewModelScope.launch {
            repository.startAdvertising(hostName).collectLatest { event ->
                when (event.type) {
                    GuestConnectionEvent.Type.Connected -> {
                        _uiState.value = _uiState.value.copy(
                            guests = _uiState.value.guests + (event.guestName ?: event.endpointId)
                        )
                    }
                    GuestConnectionEvent.Type.Disconnected -> {
                        _uiState.value = _uiState.value.copy(
                            guests = _uiState.value.guests - (event.guestName ?: event.endpointId)
                        )
                    }
                }
            }
        }
    }

    fun stopAdvertising() {
        advertisingJob?.cancel()
        viewModelScope.launch { repository.stopAdvertising() }
        _uiState.value = _uiState.value.copy(isAdvertising = false, guests = emptyList())
    }

    fun onFileSelected(uri: Uri, name: String) {
        _uiState.value = _uiState.value.copy(
            selectedFileUri = uri,
            selectedFileName = name
        )
    }

    // Placeholder: Simulate adding a guest
    fun addGuest(name: String) {
        _uiState.value = _uiState.value.copy(
            guests = _uiState.value.guests + name
        )
    }

    // Placeholder: Simulate removing a guest
    fun removeGuest(name: String) {
        _uiState.value = _uiState.value.copy(
            guests = _uiState.value.guests - name
        )
    }

    // Playback controls (UI only)
    fun play() {
        _uiState.value = _uiState.value.copy(isPlaying = true)
    }
    fun pause() {
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }
    fun seekTo(position: Long) {
        _uiState.value = _uiState.value.copy(playbackPosition = position)
    }

    fun sendFileToAllGuests() {
        val fileUri = _uiState.value.selectedFileUri ?: return
        val guests = _uiState.value.guests
        if (guests.isEmpty()) return
        // Set all to Sending
        _uiState.update { it.copy(fileTransferStatus = guests.associateWith { FileTransferStatus.Sending }) }
        viewModelScope.launch {
            try {
                repository.sendFile(guests, fileUri)
                // Set all to Sent
                _uiState.update { it.copy(fileTransferStatus = guests.associateWith { FileTransferStatus.Sent }) }
            } catch (e: Exception) {
                // Set all to Error
                _uiState.update { it.copy(fileTransferStatus = guests.associateWith { FileTransferStatus.Error }) }
            }
        }
    }

    fun sendStartPlaybackCommand(timestamp: Long) {
        val guests = _uiState.value.guests
        if (guests.isEmpty()) return
        val command = "START_PLAYBACK:$timestamp"
        viewModelScope.launch {
            repository.sendCommand(guests, command)
        }
    }
}

class HostViewModelFactory(private val repository: NearbyConnectionsRepository) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HostViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HostViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
} 