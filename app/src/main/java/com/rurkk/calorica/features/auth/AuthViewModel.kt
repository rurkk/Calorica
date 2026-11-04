package com.rurkk.calorica.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.session.SessionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Passwords are memory-only and never enter saved state, logs or toString. */
data class AuthFormState(
    val email: String = "", val password: String = "", val registration: Boolean = false,
    val busy: Boolean = false, val emailError: String? = null, val passwordError: String? = null,
    val message: String? = null, val correlationId: String? = null,
) { override fun toString() = "AuthFormState([redacted], busy=$busy)" }

fun validateCredentials(email: String, password: String): Pair<String?, String?> {
  val normalized = email.trim()
  val emailError = if (normalized.length !in 3..254 || !Regex("^[^\\s@]+@[^\\s@]+$").matches(normalized))
      "Введите корректный email (до 254 символов)." else null
  val passwordError = if (password.codePointCount(0, password.length) !in 8..128)
      "Пароль должен содержать от 8 до 128 символов." else null
  return emailError to passwordError
}

@HiltViewModel
class AuthViewModel @Inject constructor(private val session: SessionCoordinator) : ViewModel() {
  val sessionState = session.state
  private val mutableForm = MutableStateFlow(AuthFormState())
  val form = mutableForm.asStateFlow()
  fun restore() { viewModelScope.launch { session.restore() } }
  fun email(value: String) { if (!form.value.busy) mutableForm.value = form.value.copy(email = value, emailError = null, message = null) }
  fun password(value: String) { if (!form.value.busy) mutableForm.value = form.value.copy(password = value, passwordError = null, message = null) }
  fun switchMode() { if (!form.value.busy) mutableForm.value = AuthFormState(email = form.value.email, registration = !form.value.registration) }
  fun submit() {
    val input = form.value
    if (input.busy) return
    val (emailError, passwordError) = validateCredentials(input.email, input.password)
    if (emailError != null || passwordError != null) {
      mutableForm.value = input.copy(emailError = emailError, passwordError = passwordError, message = null)
      return
    }
    mutableForm.value = input.copy(busy = true, message = null, correlationId = null)
    viewModelScope.launch {
      try {
        val result = session.authenticate(Credentials(input.email.trim(), input.password), input.registration)
        mutableForm.value = when (result) {
          is ApiResult.Success -> AuthFormState(registration = input.registration)
          is ApiResult.Failure -> input.copy(password = "", busy = false,
              message = when {
                result.error.status == 401 -> "Неверный email или пароль."
                result.error.kind != ApiError.Kind.HTTP || (result.error.status ?: 500) >= 500 ->
                  if (input.registration) "Результат регистрации неизвестен. Попробуйте войти с этими данными."
                  else "Не удалось завершить вход. Попробуйте войти снова."
                else -> result.error.message()
              }, correlationId = result.error.correlationId)
        }
      } finally {
        if (form.value.busy) mutableForm.value = form.value.copy(password = "", busy = false)
      }
    }
  }
  fun logout() {
    if (form.value.busy) return
    mutableForm.value = AuthFormState(busy = true)
    viewModelScope.launch {
      try { session.logout() } finally { mutableForm.value = AuthFormState() }
    }
  }
}
