plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bronet.dictee"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bronet.dictee"
        minSdk = 26
        targetSdk = 34
        versionCode = 166
        versionName = "166-android"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signature de débogage : suffisante pour installer l'APK à la main (sideload).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Traduction hors ligne (modèles téléchargés une fois par langue)
    implementation("com.google.mlkit:translate:17.0.3")
}
