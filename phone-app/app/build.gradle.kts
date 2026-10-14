import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.watchmouse"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.watchmouse"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val keystorePath = signingProperties.getProperty("releaseStoreFile")
                ?: error("releaseStoreFile is missing from phone-app/local.properties")
            storeFile = rootProject.file(keystorePath)
            storePassword = signingProperties.getProperty("releaseStorePassword")
                ?: error("releaseStorePassword is missing from phone-app/local.properties")
            keyAlias = signingProperties.getProperty("releaseKeyAlias")
                ?: error("releaseKeyAlias is missing from phone-app/local.properties")
            keyPassword = signingProperties.getProperty("releaseKeyPassword")
                ?: error("releaseKeyPassword is missing from phone-app/local.properties")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
