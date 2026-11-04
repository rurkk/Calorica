package com.rurkk.calorica.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rurkk.calorica.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private data class SessionUiKey(val accountId: String, val generation: Long)

/** The Activity retains this holder; only a different session or its final destruction clears it. */
internal class SessionUiStores(session: StateFlow<SessionState>) : ViewModel() {
  private var activeKey: SessionUiKey? = null
  private var activeStore: ViewModelStore? = null

  init {
    // Keep observing while the Activity is stopped, so logout also cancels old feature work then.
    viewModelScope.launch { session.collect(::storeFor) }
  }

  fun storeFor(session: SessionState): ViewModelStore? {
    val next = (session as? SessionState.SignedIn)?.let { SessionUiKey(it.account.id, it.generation) }
    if (next != activeKey) {
      activeStore?.clear()
      activeKey = next
      activeStore = next?.let { ViewModelStore() }
    }
    return activeStore
  }

  override fun onCleared() {
    activeStore?.clear()
    activeStore = null
    activeKey = null
  }

  companion object {
    fun factory(session: StateFlow<SessionState>): ViewModelProvider.Factory = viewModelFactory {
      initializer { SessionUiStores(session) }
    }
  }
}

/** Must stay composed for signed-out states too; rotation is not the end of a login session. */
@Composable
internal fun SessionUiScope(session: StateFlow<SessionState>, content: @Composable (SessionState) -> Unit) {
  val current by session.collectAsStateWithLifecycle()
  val parent = requireNotNull(LocalViewModelStoreOwner.current)
  val stores: SessionUiStores = viewModel(factory = remember(session) { SessionUiStores.factory(session) })
  // Also synchronize at use time, before any new account can obtain a feature ViewModel.
  val store = stores.storeFor(current)
  if (current is SessionState.SignedIn && store != null) {
    val signedIn = current as SessionState.SignedIn
    val defaults = parent as HasDefaultViewModelProviderFactory
    val owner = remember(parent, store) {
      object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore = requireNotNull(store)
        override val defaultViewModelProviderFactory get() = defaults.defaultViewModelProviderFactory
        override val defaultViewModelCreationExtras get() = defaults.defaultViewModelCreationExtras
      }
    }
    key(signedIn.account.id, signedIn.generation) {
      CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content(current) }
    }
  } else {
    content(current)
  }
}
