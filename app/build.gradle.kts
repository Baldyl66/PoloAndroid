plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.dylan.polo"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.dylan.polo"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        // autoradios et téléphones ARM uniquement : APK bien plus léger
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Clé de signature fixe : sans elle, chaque construction GitHub signe différemment
    // et Android refuse d'installer la mise à jour par-dessus l'ancienne version.
    signingConfigs {
        // fournie par GitHub (secrets POLO_KEYSTORE_B64 / POLO_KEYSTORE_PASSWORD), jamais dans le dépôt
        val ks = rootProject.file("polo.keystore")
        val pass = System.getenv("POLO_KEYSTORE_PASSWORD")
        if (ks.exists() && !pass.isNullOrEmpty()) create("polo") {
            storeFile = ks
            storePassword = pass
            keyAlias = "polo"
            keyPassword = pass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signée avec la clé de debug pour pouvoir l'installer directement sur l'autoradio.
            signingConfig = signingConfigs.findByName("polo") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.8.0")
    // reconnaissance vocale hors ligne pour le réveil « Polo »
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // voix naturelles hors ligne (Piper via sherpa-onnx) ; la bibliothèque est téléchargée par GitHub Actions
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
