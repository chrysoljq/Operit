plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ai.assistance.operit.uiautoserver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ai.assistance.operit.uiautoserver"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        aidl = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    // UIAutomator Core
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.test:core:1.5.0")
}
