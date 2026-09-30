import java.util.Properties
plugins {
  id("com.android.application"); id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp")
}
val ks = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
android {
  namespace = "com.spendpop.app"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.spendpop.app"   // change to your own reverse-domain ID BEFORE first upload (permanent)
    minSdk = 26; targetSdk = 36          // Play raises the required targetSdk yearly: check before each release
    versionCode = 1; versionName = "1.0.0"   // bump versionCode on EVERY update
  }
  flavorDimensions += "dist"
  productFlavors {
    create("personal") { dimension = "dist"; applicationIdSuffix = ".personal"; buildConfigField("boolean", "SMS", "true") }   // sideload: auto SMS capture
    create("play")     { dimension = "dist"; buildConfigField("boolean", "SMS", "false") }                                    // Play: NO SMS permissions
  }
  signingConfigs { create("release") { if (ks.isNotEmpty()) {
    storeFile = file(ks["storeFile"] as String); storePassword = ks["storePassword"] as String
    keyAlias = ks["keyAlias"] as String; keyPassword = ks["keyPassword"] as String } } }
  buildTypes { release {
    isMinifyEnabled = true; isShrinkResources = true; isDebuggable = false
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    if (ks.isNotEmpty()) signingConfig = signingConfigs.getByName("release") } }
  buildFeatures { compose = true; buildConfig = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.10.01"))
  implementation("androidx.compose.material3:material3"); implementation("androidx.compose.foundation:foundation")
  implementation("androidx.activity:activity-compose:1.9.3"); implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.biometric:biometric:1.1.0")
  implementation("androidx.room:room-ktx:2.6.1"); ksp("androidx.room:room-compiler:2.6.1")
  implementation("net.zetetic:sqlcipher-android:4.5.7"); implementation("androidx.sqlite:sqlite-ktx:2.4.0")
  implementation("androidx.work:work-runtime-ktx:2.9.1")
  implementation("com.google.android.gms:play-services-auth:21.2.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
  implementation("com.google.android.play:app-update-ktx:2.1.0")
}
