import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rokid.translator"
    compileSdk = 36

    val localPropsFile = rootProject.file("local.properties")
    val localProps = Properties().apply {
        if (localPropsFile.exists()) localPropsFile.inputStream().use { load(it) }
    }

    defaultConfig {
        applicationId = "com.rokid.translator"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProps.getProperty("GEMINI_API_KEY", "")}\"")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":common"))
    
    // AndroidX
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.2")
    
    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    
    // Gemini AI
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    
    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    
    // Gson
    implementation("com.google.code.gson:gson:2.13.2")
    
    // Bluetooth
    implementation("androidx.bluetooth:bluetooth:1.0.0-alpha02")
    
    // Rokid CXR-M SDK
    implementation("com.rokid.cxr:client-m:1.0.4")
    
    // CXR SDK dependencies
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")
    implementation("com.squareup.okio:okio:3.16.4")
    
    // Security Crypto for encrypted prefs
    implementation("androidx.security:security-crypto:1.1.0")
    
    // ML Kit Translation
    implementation("com.google.mlkit:translate:17.0.3")
    
    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
