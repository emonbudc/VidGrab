package com.vidgrab.app.download

import com.vidgrab.app.model.ActiveDownload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadBus {
    private val _state = MutableStateFlow<ActiveDownload?>(null)
    val state: StateFlow<ActiveDownload?> = _state.asStateFlow()

    fun update(value: ActiveDownload?) {
        _state.value = value
    }
}
