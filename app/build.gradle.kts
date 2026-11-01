plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
}

// Change these when backward compatibility with API or persisted data breaks.
extra["caloricaApiEpoch"] = 1
extra["caloricaDataEpoch"] = 1

android {
  namespace = "com.rurkk.calorica"
  compileSdk { version = release(37) }

  defaultConfig {
    applicationId = "com.rurkk.calorica"
    minSdk = 26
    targetSdk = 37
    versionCode = providers.environmentVariable("CALORICA_VERSION_CODE").orNull?.toInt() ?: 3
    versionName = providers.environmentVariable("CALORICA_VERSION_NAME").orNull ?: "0.1.2"
  }

  buildFeatures { compose = true }
  testOptions { unitTests { isIncludeAndroidResources = true } }

  // CI supplies a persistent key; a local release without secrets stays unsigned.
  val releaseStore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
  if (releaseStore != null) {
    signingConfigs {
      create("release") {
        storeFile = file(releaseStore)
        storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
        keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
        keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
      }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
  }
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
