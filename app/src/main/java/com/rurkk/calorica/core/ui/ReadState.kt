package com.rurkk.calorica.core.ui

import com.rurkk.calorica.core.api.ApiError
import com.rurkk.calorica.core.api.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ReadState<out T> {
  data object Idle : ReadState<Nothing>
  data object Loading : ReadState<Nothing>
  data class Empty<T>(val value: T) : ReadState<T>
  data class Data<T>(val value: T) : ReadState<T>
  data class Error(val error: ApiError) : ReadState<Nothing>
}

/** Memory only, owned by a screen's ViewModel. Closing a screen cancels its read. */
class ReadController<T>(private val scope: CoroutineScope, private val isEmpty: (T) -> Boolean) {
  private val mutableState = MutableStateFlow<ReadState<T>>(ReadState.Idle)
  val state: StateFlow<ReadState<T>> = mutableState.asStateFlow()
  private var job: Job? = null
  private var generation = 0
  fun load(read: suspend () -> ApiResult<T>) {
    if (job?.isActive == true) return
    val request = ++generation
    mutableState.value = ReadState.Loading
    job = scope.launch {
      val result = read()
      if (generation == request) mutableState.value = when (result) {
        is ApiResult.Success -> if (isEmpty(result.value)) ReadState.Empty(result.value) else ReadState.Data(result.value)
        is ApiResult.Failure -> ReadState.Error(result.error)
      }
    }
  }
  fun close() {
    generation++
    job?.cancel()
    job = null
    mutableState.value = ReadState.Idle
  }
}
