package com.rurkk.calorica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.rurkk.calorica.ui.theme.CaloricaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { CaloricaTheme { CaloricaApp() } }
  }
}

@Composable
private fun CaloricaApp() {
  Surface(modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
      Text("Calorica", style = MaterialTheme.typography.headlineLarge)
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun CaloricaAppPreview() {
  CaloricaTheme { CaloricaApp() }
}
