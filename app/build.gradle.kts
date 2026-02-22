plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.cnnmodelandimu"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.cnnmodelandimu"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // This is often required when using select-tf-ops
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // 1. Core TFLite Runtime (Use 2.16.1, which is stable and modern)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // 2. Select TF Ops (REQUIRED for your LSTM model)
    // This allows the app to run the specific LSTM math operations
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.16.1")

    // 3. Load the Support library, but BLOCK it from bringing its own old TFLite
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4") {
        exclude(group = "org.tensorflow", module = "tensorflow-lite")
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    }

    // 1. For AppCompatActivity
    implementation("androidx.appcompat:appcompat:1.6.1")
    // 2. For ViewModel and ViewModelProvider
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    // 3. For lifecycleScope (Coroutines in Activities)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    // 4. For Flow .collect {}
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.github.mik3y:usb-serial-for-android:3.7.3")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}