package com.rurkk.calorica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.rurkk.calorica.core.navigation.CaloricaNavHost
import com.rurkk.calorica.core.ui.ConnectionViewModel
import com.rurkk.calorica.ui.theme.CaloricaTheme
import com.rurkk.calorica.features.auth.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.rurkk.calorica.core.data.ProductRepository
import com.rurkk.calorica.core.data.DiaryRepository
import com.rurkk.calorica.core.session.SessionCoordinator
import com.rurkk.calorica.features.products.ProductsFeatureScreens

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  @Inject lateinit var products: ProductRepository
  @Inject lateinit var diary: DiaryRepository
  @Inject lateinit var session: SessionCoordinator
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val connection = ViewModelProvider(this)[ConnectionViewModel::class.java]
    val auth = ViewModelProvider(this)[AuthViewModel::class.java]
    setContent { CaloricaTheme { CaloricaNavHost(connection, auth, ProductsFeatureScreens(products,diary,session)) } }
  }
}
