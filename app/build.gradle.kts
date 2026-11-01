plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
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
    val apiUrl = providers.gradleProperty("caloricaApiBaseUrl")
        .orElse("https://calorica-backend.onrender.com/").get()
    require(apiUrl.startsWith("https://") && apiUrl.endsWith("/") && !apiUrl.contains('"') && !apiUrl.contains('\\')) {
      "caloricaApiBaseUrl must be an HTTPS URL ending in /"
    }
    buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
    minSdk = 26
    targetSdk = 37
    versionCode = providers.environmentVariable("CALORICA_VERSION_CODE").orNull?.toInt() ?: 4
    versionName = providers.environmentVariable("CALORICA_VERSION_NAME").orNull ?: "0.1.3"
  }

  buildFeatures { compose = true; buildConfig = true }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  sourceSets.getByName("test").resources.srcDir("../docs/semester-mvp/contracts/v1")

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
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.retrofit)
  implementation(libs.retrofit.serialization)
  implementation(libs.okhttp)
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  debugImplementation(libs.androidx.ui.tooling)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockwebserver)
}
