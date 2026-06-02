plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "com.drduc.legado"
  compileSdk = 36

  defaultConfig { minSdk = 24 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  api(project(":modules:rhino"))
  implementation(libs.okhttp)
  implementation(libs.jsoup)
  implementation(libs.json.path)
  implementation(libs.jsoup.xpath)
  implementation(libs.commons.text)
  implementation(libs.kotlinx.coroutines.core)
}
