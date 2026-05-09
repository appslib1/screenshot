plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.screenshot_capture.screenshot_photo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.screenshot_capture.screenshot_photo"
        minSdk = 21
        targetSdk = 35
        versionCode = 17
        versionName = "1.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation("com.github.bumptech.glide:glide:5.0.5")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}