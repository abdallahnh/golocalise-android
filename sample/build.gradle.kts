import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun demoValue(name: String): String =
  providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orElse("").get()

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "com.golocalise.sample"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.golocalise.sample"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }
  buildFeatures { compose = true; buildConfig = true }
  defaultConfig {
    buildConfigField("String", "GOLOCALISE_BASE_URL", "\"${demoValue("GOLOCALISE_BASE_URL")}\"")
    buildConfigField("String", "GOLOCALISE_SDK_TOKEN", "\"${demoValue("GOLOCALISE_SDK_TOKEN")}\"")
    buildConfigField("String", "GOLOCALISE_PROJECT_ID", "\"${demoValue("GOLOCALISE_PROJECT_ID")}\"")
    buildConfigField("String", "GOLOCALISE_ENVIRONMENT", "\"${demoValue("GOLOCALISE_ENVIRONMENT").ifEmpty { "production" }}\"")
    buildConfigField("String", "GOLOCALISE_DEFAULT_LOCALE", "\"${demoValue("GOLOCALISE_DEFAULT_LOCALE").ifEmpty { "en" }}\"")
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
  implementation(project(":golocalise"))
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.compose.foundation:foundation:1.8.3")
  implementation("androidx.compose.material3:material3:1.3.2")
  implementation("androidx.compose.ui:ui:1.8.3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
