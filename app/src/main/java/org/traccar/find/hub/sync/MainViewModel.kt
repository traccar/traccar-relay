package org.traccar.find.hub.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStorage = TokenStorage(application)

    private val _token = MutableStateFlow(tokenStorage.getToken())
    val token: StateFlow<String?> = _token

    fun onTokenReceived(token: String) {
        tokenStorage.saveToken(token)
        _token.value = token
    }
}
