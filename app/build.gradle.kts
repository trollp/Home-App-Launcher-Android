plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.petertroll.quiethome"
    compileSdk = 36

    flavorDimensions += "capabilities"

    defaultConfig {
        applicationId = "com.petertroll.quiethome"
        minSdk = 26
        targetSdk = 34
        versionCode = 18
        versionName = "0.19.0-planner-phase3"
    }
    productFlavors {
        create("full") {
            dimension = "capabilities"
        }
        create("safe") {
            dimension = "capabilities"
            versionCode = 18
            versionName = "0.19.0-planner-phase3-bank-safe"
        }
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    testOptions { unitTests.isIncludeAndroidResources = false }
    lint {
        disable += "GradleDependency"
        // AAPT requires a non-adaptive fallback even though adaptive icons are supported at minSdk 26.
        disable += "ObsoleteSdkInt"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
