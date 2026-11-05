package com.rurkk.calorica.features.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AuthScreen(viewModel: AuthViewModel, sessionMessage: String?) {
  val state by viewModel.form.collectAsStateWithLifecycle()
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(if (state.registration) "Создать аккаунт" else "Войти в аккаунт", style = MaterialTheme.typography.headlineSmall)
    Text("Для начала нужны только email и пароль.")
    sessionMessage?.let { Text(it) }
    OutlinedTextField(value = state.email, onValueChange = viewModel::email,
        label = { Text("Email") }, singleLine = true, enabled = !state.busy,
        isError = state.emailError != null, supportingText = { state.emailError?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress })
    OutlinedTextField(value = state.password, onValueChange = viewModel::password,
        label = { Text("Пароль") }, singleLine = true, enabled = !state.busy,
        isError = state.passwordError != null, supportingText = { state.passwordError?.let { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().semantics { contentType = if (state.registration) ContentType.NewPassword else ContentType.Password })
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.correlationId?.let { Text("Код обращения: $it", style = MaterialTheme.typography.bodySmall) }
    if (state.busy) CircularProgressIndicator()
    Button(onClick = viewModel::submit, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
      Text(if (state.registration) "Зарегистрироваться" else "Войти")
    }
    TextButton(onClick = viewModel::switchMode, enabled = !state.busy) {
      Text(if (state.registration) "Уже есть аккаунт — войти" else "Создать аккаунт")
    }
  }
}
