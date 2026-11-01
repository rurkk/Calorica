package com.rurkk.calorica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.rurkk.calorica.core.navigation.CaloricaNavHost
import com.rurkk.calorica.core.ui.ConnectionViewModel
import com.rurkk.calorica.ui.theme.CaloricaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val connection = ViewModelProvider(this)[ConnectionViewModel::class.java]
    setContent { CaloricaTheme { CaloricaNavHost(connection) } }
  }
}
