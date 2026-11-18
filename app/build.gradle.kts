plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gpaudio.gpmix"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gpaudio.gpmix"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }

    // Tiga edisi dari satu kode: batas jumlah channel/insert/send dibaca dari BuildConfig.EDITION.
    flavorDimensions += "edition"
    productFlavors {
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            buildConfigField("String", "EDITION", "\"lite\"")
            manifestPlaceholders["appLabel"] = "GP Mix Lite"
        }
        create("plus") {
            dimension = "edition"
            applicationIdSuffix = ".plus"
            versionNameSuffix = "-plus"
            buildConfigField("String", "EDITION", "\"plus\"")
            manifestPlaceholders["appLabel"] = "GP Mix Plus"
        }
        create("pro") {
            dimension = "edition"
            applicationIdSuffix = ".pro"
            versionNameSuffix = "-pro"
            buildConfigField("String", "EDITION", "\"pro\"")
            manifestPlaceholders["appLabel"] = "GP Mix Pro"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Release signing hanya aktif kalau KEYSTORE_PATH diset (lihat workflow).
    val ksPath: String? = System.getenv("KEYSTORE_PATH")
    if (ksPath != null) {
        signingConfigs.create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("com.google.oboe:oboe:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
