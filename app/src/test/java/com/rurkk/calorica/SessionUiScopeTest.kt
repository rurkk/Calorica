package com.rurkk.calorica

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rurkk.calorica.core.api.Account
import com.rurkk.calorica.core.api.Profile
import com.rurkk.calorica.core.navigation.SessionUiScope
import com.rurkk.calorica.core.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercises production SessionUiScope through a real Activity and Navigation Compose lifecycle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SessionUiScopeTest {
  @get:Rule val compose = createEmptyComposeRule()
  private lateinit var activity: ActivityController<SessionScopeTestActivity>

  @Before fun start() {
    SessionScopeTestActivity.session = MutableStateFlow(signedIn("account-a", 1))
    SessionScopeTestActivity.editor = null
    activity = Robolectric.buildActivity(SessionScopeTestActivity::class.java).setup().visible()
    compose.waitForIdle()
  }

  @After fun finish() {
    activity.pause().stop().destroy()
  }

  @Test fun recreationKeepsDraftPendingMutationAndPickerBackStack() {
    val before = requireNotNull(SessionScopeTestActivity.editor)
    compose.runOnIdle {
      before.draft = "unsaved product"
      before.save()
      SessionScopeTestActivity.nav.navigate("picker")
    }
    compose.waitForIdle()
    compose.runOnIdle {
      SessionScopeTestActivity.nav.previousBackStackEntry!!.savedStateHandle["picker-result"] = "selected-product"
      activity.recreate()
    }
    compose.waitForIdle()
    compose.runOnIdle {
      val nav = SessionScopeTestActivity.nav
      assertEquals("picker", nav.currentDestination!!.route)
      assertEquals("selected-product", nav.previousBackStackEntry!!.savedStateHandle.get<String>("picker-result"))
      assertFalse(before.cleared)
      assertTrue(before.saving)
      assertEquals(1, before.requestCount)
      nav.popBackStack()
    }
    compose.waitForIdle()
    compose.runOnIdle {
      assertSame(before, SessionScopeTestActivity.editor)
      assertEquals("unsaved product", before.draft)
      before.response.complete(Unit)
    }
    compose.waitForIdle()
    compose.runOnIdle {
      assertTrue(before.saved)
      assertFalse(before.saving)
      assertEquals(1, before.requestCount)
    }
  }

  @Test fun logoutClearsPendingWorkAndNewLoginGetsEmptyNavigationAndDraft() {
    val before = requireNotNull(SessionScopeTestActivity.editor)
    compose.runOnIdle {
      before.draft = "private draft"
      before.save()
      SessionScopeTestActivity.nav.navigate("picker")
      SessionScopeTestActivity.session.value = SessionState.SignedOut()
    }
    compose.waitForIdle()
    compose.runOnIdle {
      assertTrue(before.cleared)
      assertFalse(before.saved)
      assertFalse(before.saving)
      SessionScopeTestActivity.session.value = signedIn("account-a", 2)
    }
    compose.waitForIdle()
    compose.runOnIdle {
      val after = requireNotNull(SessionScopeTestActivity.editor)
      assertNotSame(before, after)
      assertEquals("", after.draft)
      assertEquals("editor", SessionScopeTestActivity.nav.currentDestination!!.route)
      assertNull(SessionScopeTestActivity.nav.previousBackStackEntry)
      before.response.complete(Unit)
    }
    compose.waitForIdle()
    assertFalse(before.saved)
  }

  @Test fun accountChangeClearsOldStoreEvenWhenActivityIsStopped() {
    val before = requireNotNull(SessionScopeTestActivity.editor)
    compose.runOnIdle {
      before.draft = "account-a only"
      activity.pause().stop()
      // Include the account in the key even if a caller accidentally reuses the generation.
      SessionScopeTestActivity.session.value = signedIn("account-b", 1)
    }
    compose.waitForIdle()
    assertTrue(before.cleared)
    compose.runOnIdle { activity.restart().start().resume().visible() }
    compose.waitForIdle()
    compose.runOnIdle {
      assertNotSame(before, SessionScopeTestActivity.editor)
      assertEquals("", SessionScopeTestActivity.editor!!.draft)
    }
  }

  private fun signedIn(id: String, generation: Long) = SessionState.SignedIn(
      Account(id, "$id@example.test", "USER", Profile(null, null, null, null)), generation)
}

class SessionScopeTestActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      SessionUiScope(session) { state ->
        if (state is SessionState.SignedIn) {
          val controller = rememberNavController()
          nav = controller
          NavHost(controller, "editor") {
            composable("editor") {
              val model: SessionDraftViewModel = viewModel()
              editor = model
              Text(model.draft)
            }
            composable("picker") { Text("Product picker") }
          }
        } else Text("Signed out")
      }
    }
  }

  companion object {
    lateinit var session: MutableStateFlow<SessionState>
    lateinit var nav: NavHostController
    var editor: SessionDraftViewModel? = null
  }
}

class SessionDraftViewModel : ViewModel() {
  var draft by mutableStateOf("")
  var saving = false
  var saved = false
  var cleared = false
  var requestCount = 0
  val response = CompletableDeferred<Unit>()

  fun save() {
    if (saving) return
    saving = true
    requestCount++
    viewModelScope.launch {
      try { response.await(); saved = true } finally { saving = false }
    }
  }

  override fun onCleared() { cleared = true }
}
