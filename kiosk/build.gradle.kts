plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.azurate.brewpos.kiosk"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.azurate.brewpos.kiosk"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-reference-ui-preview"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Image loading/cache for generic café visuals; no private keys or menu mock data.
    implementation("io.coil-kt:coil-compose:2.7.0")
}
