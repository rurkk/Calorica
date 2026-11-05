package com.rurkk.calorica.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rurkk.calorica.core.data.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel class ConnectionViewModel @Inject constructor(private val repository: ConnectionRepository) : ViewModel() {
  private val warmup = ServerWarmupController(viewModelScope) { repository.check() }
  val startupState = warmup.state
  fun startWarmup() = warmup.start()
  fun retryWarmup() = warmup.retry()
  fun pauseWarmup() = warmup.pause()
}
