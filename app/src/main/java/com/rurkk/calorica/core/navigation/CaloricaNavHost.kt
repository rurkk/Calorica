package com.rurkk.calorica.core.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.rurkk.calorica.core.ui.ConnectionViewModel
import com.rurkk.calorica.core.ui.ReadState
import com.rurkk.calorica.core.ui.ServerWarmupState
import com.rurkk.calorica.core.ui.ServerWarmupScreen
import com.rurkk.calorica.core.session.SessionState
import com.rurkk.calorica.features.auth.AuthScreen
import com.rurkk.calorica.features.auth.AuthViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/** Feature owners implement slots; only agent 4 edits root routes/DI. */
interface FeatureScreens {
  @Composable fun Auth(onAuthenticated: () -> Unit)
  @Composable fun Products(picker: ProductPickerHost)
  @Composable fun Diary(picker: ProductPickerHost)
  @Composable fun ProductPicker(request: ProductSelectionRequest, onSelected: (ProductSelection) -> Unit, onCancel: () -> Unit)
}

object FoundationScreens : FeatureScreens {
  @Composable override fun Auth(onAuthenticated: () -> Unit) {
    FoundationSection("Вход в аккаунт", "Экран входа готовится.")
  }
  @Composable override fun Products(picker: ProductPickerHost) {
    FoundationSection("Продукты", "Каталог продуктов готовится.")
  }
  @Composable override fun Diary(picker: ProductPickerHost) {
    FoundationSection("Дневник", "Дневник питания готовится.") {
      OutlinedButton(onClick = { picker.launch(ProductSelectionRequest.create(ProductSelectionPurpose.DIARY)) }) {
        Text("Выбрать продукт")
      }
    }
  }
  @Composable override fun ProductPicker(request: ProductSelectionRequest, onSelected: (ProductSelection) -> Unit, onCancel: () -> Unit) {
    FoundationSection("Выбор продукта", "Выбор продуктов готовится.") {
      OutlinedButton(onClick = onCancel) { Text("Отмена") }
    }
  }
}

@Composable
private fun FoundationSection(title: String, description: String, content: @Composable () -> Unit = {}) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(description)
    content()
  }
}

fun NavHostController.openProductPicker(request: ProductSelectionRequest) { navigate(request.route) }

@Composable
fun CaloricaNavHost(connection: ConnectionViewModel, auth: AuthViewModel, screens: FeatureScreens = FoundationScreens) {
  val startup by connection.startupState.collectAsStateWithLifecycle()
  val owner = LocalLifecycleOwner.current
  DisposableEffect(connection, owner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> connection.startWarmup()
        Lifecycle.Event.ON_STOP -> connection.pauseWarmup()
        else -> Unit
      }
    }
    owner.lifecycle.addObserver(observer)
    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) connection.startWarmup()
    onDispose { owner.lifecycle.removeObserver(observer) }
  }
  val session by auth.sessionState.collectAsStateWithLifecycle()
  LaunchedEffect(startup) { if (startup == ServerWarmupState.Ready) auth.restore() }
  Surface(Modifier.fillMaxSize()) {
    if (startup != ServerWarmupState.Ready) {
      ServerWarmupScreen(startup, connection::retryWarmup)
    } else when (val current = session) {
      SessionState.Restoring -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(); Text("Восстанавливаем сессию…")
      }
      is SessionState.SignedOut -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Calorica", style = MaterialTheme.typography.headlineLarge)
        AuthScreen(auth, current.message)
      }
      is SessionState.SignedIn -> key(current.generation) {
        SessionUiScope { AuthenticatedNavigation(connection, screens, current.account.email, auth::logout) }
      }
    }
  }
}

/** Discard every feature ViewModel and navigation result when an account/session changes. */
@Composable
private fun SessionUiScope(content: @Composable () -> Unit) {
  val parent = requireNotNull(LocalViewModelStoreOwner.current)
  val defaults = parent as HasDefaultViewModelProviderFactory
  val owner = remember(parent) {
    object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
      override val viewModelStore = ViewModelStore()
      override val defaultViewModelProviderFactory get() = defaults.defaultViewModelProviderFactory
      override val defaultViewModelCreationExtras get() = defaults.defaultViewModelCreationExtras
    }
  }
  DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
  CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

@Composable
private fun AuthenticatedNavigation(connection: ConnectionViewModel, screens: FeatureScreens, email: String, onLogout: () -> Unit) {
  val nav = rememberNavController()
  val backStack by nav.currentBackStackEntryAsState()
  val current = backStack?.destination?.route
  Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Calorica", style = MaterialTheme.typography.headlineLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(email, modifier = Modifier.weight(1f))
      TextButton(onClick = onLogout) { Text("Выйти") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { nav.navigate(Routes.DIARY) { launchSingleTop = true } }) { Text("Дневник") }
      OutlinedButton(onClick = { nav.navigate(Routes.PRODUCTS) { launchSingleTop = true } }) { Text("Продукты") }
    }
    if (current != Routes.CONNECTION) {
      TextButton(onClick = { nav.navigate(Routes.CONNECTION) { launchSingleTop = true } }) { Text("Связь с сервером") }
    }
    if (nav.previousBackStackEntry != null) {
      TextButton(onClick = { nav.popBackStack() }) { Text("Назад") }
    }
    NavHost(navController = nav, startDestination = Routes.DIARY, modifier = Modifier.weight(1f)) {
      composable(Routes.PRODUCTS) { entry -> screens.Products(rememberProductPickerHost(nav, entry)) }
      composable(Routes.DIARY) { entry -> screens.Diary(rememberProductPickerHost(nav, entry)) }
      composable(Routes.PRODUCT_PICKER) { entry ->
        val request = ProductSelectionRequest(
            requireNotNull(entry.arguments?.getString("requestId")),
            ProductSelectionPurpose.valueOf(requireNotNull(entry.arguments?.getString("purpose"))),
        )
        screens.ProductPicker(request, onSelected = { result ->
          nav.previousBackStackEntry?.savedStateHandle?.set(ProductSelectionResult.STATE_KEY, ProductSelectionResult(request, result).encode())
          nav.popBackStack()
        }, onCancel = { nav.popBackStack() })
      }
      composable(Routes.CONNECTION) { ConnectionScreen(connection) }
    }
  }
}

@Composable
private fun ConnectionScreen(viewModel: ConnectionViewModel) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  DisposableEffect(viewModel) { onDispose { viewModel.close() } }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Связь с сервером", style = MaterialTheme.typography.headlineSmall)
    when (val value = state) {
      ReadState.Idle -> Text("Проверьте доступность сервера.")
      ReadState.Loading -> { CircularProgressIndicator(); Text("Проверяем соединение…") }
      is ReadState.Data -> Text(if (value.value.status == "UP") "Сервер доступен" else "Сервер временно недоступен")
      is ReadState.Empty -> Text("Ответ сервера пуст.")
      is ReadState.Error -> {
        Text(value.error.message())
        value.error.correlationId?.let { Text("Код обращения: $it") }
      }
    }
    Button(onClick = viewModel::load, enabled = state != ReadState.Loading) {
      Text(if (state == ReadState.Idle) "Проверить соединение" else "Повторить загрузку")
    }
  }
}

@Composable
private fun rememberProductPickerHost(nav: NavHostController, entry: NavBackStackEntry): ProductPickerHost {
  val encoded by entry.savedStateHandle.getStateFlow<String?>(ProductSelectionResult.STATE_KEY, null).collectAsStateWithLifecycle()
  return ProductPickerHost(
      result = encoded?.let(ProductSelectionResult::decode),
      launch = nav::openProductPicker,
      consume = { entry.savedStateHandle[ProductSelectionResult.STATE_KEY] = null },
  )
}
