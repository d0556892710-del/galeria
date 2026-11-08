plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.galeria.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.galeria.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 10
        versionName = "3.7"
        // Every phone in use today is 64-bit ARM; leaving out the other chips keeps the app small.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") {
            // Fixed key so every new build installs over the previous one.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    androidResources {
        generateLocaleConfig = false
    }

    buildTypes {
        release {
            // Optimised build: R8 + no debug overhead. Debug builds of Compose run several times slower.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
    buildFeatures { compose = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.biometric)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    // On-device vision (bundled models, works offline): content search, Live Text, faces.
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation(libs.androidx.exifinterface)
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
