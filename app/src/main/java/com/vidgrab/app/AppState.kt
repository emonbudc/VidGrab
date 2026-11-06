package com.vidgrab.app

import kotlinx.coroutines.flow.MutableStateFlow

object AppState {
    val sharedUrl = MutableStateFlow<String?>(null)
}
