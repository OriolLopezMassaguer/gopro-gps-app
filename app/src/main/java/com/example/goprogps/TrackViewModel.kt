package com.example.goprogps

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.goprogps.model.GpsTrack
import com.example.goprogps.parser.GpmfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrackViewModel : ViewModel() {

    private companion object {
        const val TAG = "TrackViewModel"
    }

    sealed class State {
        object Idle : State()
        object Loading : State()
        data class Success(val track: GpsTrack) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun loadTrack(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _state.value = State.Loading
            _state.value = withContext(Dispatchers.IO) {
                try {
                    val track = contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        GpmfParser.parse(pfd.fileDescriptor)
                    }
                    if (track != null && track.points.isNotEmpty()) {
                        State.Success(track)
                    } else {
                        Log.e(TAG, "Parser returned null or empty track for $uri")
                        State.Error("No GPS data found in this video.\nMake sure GPS was enabled during recording.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception while loading track from $uri", e)
                    State.Error(e.message ?: "Failed to read video file.")
                }
            }
        }
    }
}
