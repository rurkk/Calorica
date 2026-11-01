package com.rurkk.calorica.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ServerWarmupScreen(state: ServerWarmupState, onRetry: () -> Unit) {
  Column(
      Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Calorica", style = MaterialTheme.typography.headlineLarge)
    if (state is ServerWarmupState.Failed) {
      Text("Не удалось подключиться к серверу", style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
      Text(if (state.deadlineExceeded) "Сервер пока не отвечает. Проверьте интернет и повторите подключение."
          else "Сервер временно недоступен. Попробуйте подключиться позже.")
      state.error?.correlationId?.let { Text("Код обращения: $it") }
      Button(onClick = onRetry) { Text("Повторить") }
    } else {
      CircularProgressIndicator()
      Text("Сервер просыпается…", style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
      Text("Подключение может занять до 90 секунд.")
    }
  }
}
