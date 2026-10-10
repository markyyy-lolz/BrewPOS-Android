plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.brewpos.kiosk"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.brewpos.kiosk"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    sourceSets.getByName("main").assets.srcDir("../ui")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
}
