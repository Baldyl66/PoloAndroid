plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.dylan.polo.compagnon"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.dylan.polo.compagnon"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
            // Signée avec la clé de debug pour pouvoir l'installer directement sur le téléphone.
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
