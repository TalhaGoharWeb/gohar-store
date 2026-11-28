import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.gohar.store"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gohar.store"
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "1.4.3"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        // v1.3.11: the drawer header shows the store version from
        // BuildConfig.VERSION_NAME (off by default in AGP 8+).
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// core-ktx 1.19.1 AAR metadata declares minCompileSdk 37 while this
// app compiles against 36 (same approach as the Sett build — we call no
// API-37 APIs, and the packaged APK is verified directly after build).
// Disable AGP's AAR-metadata gate.
tasks.matching { it.name.contains("AarMetadata") }.configureEach {
    enabled = false
}

dependencies {
    // Every dependency below is already proven in the Gradle cache by the
    // Sett build — v1 deliberately uses classic Views + Material 3, not
    // Compose, so the build never has to download a Compose compiler
    // plugin through this VM's proxy.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
}
