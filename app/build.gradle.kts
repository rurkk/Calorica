plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
}

android {
  namespace = "com.rurkk.calorica"
  compileSdk { version = release(37) }

  defaultConfig {
    applicationId = "com.rurkk.calorica"
    minSdk = 26
    targetSdk = 37
    versionCode = 2
    versionName = "0.1.1"
  }

  buildFeatures { compose = true }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { jvmToolchain(17) }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.activity.compose)
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  debugImplementation(libs.androidx.ui.tooling)
  testImplementation(libs.junit)
}
