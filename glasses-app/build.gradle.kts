import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rokid.translator.glasses"
    compileSdk = 36

    val botPropsFile = rootProject.file("local.bot.properties")
    val localPropsFile = rootProject.file("local.properties")
    val mergedProps = Properties().apply {
        if (localPropsFile.exists()) localPropsFile.inputStream().use { load(it) }
        if (botPropsFile.exists()) botPropsFile.inputStream().use { load(it) }
    }

    defaultConfig {
        applicationId = "com.rokid.translator.glasses"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${mergedProps.getProperty("OPENROUTER_API_KEY", "")}\"")
        buildConfigField("String", "OPENROUTER_MODEL", "\"${mergedProps.getProperty("OPENROUTER_MODEL", "openai/gpt-4.1-mini")}\"")
        buildConfigField("String", "OPENROUTER_SITE_URL", "\"${mergedProps.getProperty("OPENROUTER_SITE_URL", "")}\"")
        buildConfigField("String", "OPENROUTER_APP_NAME", "\"${mergedProps.getProperty("OPENROUTER_APP_NAME", "Rokid Translator")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("com.google.mlkit:translate:17.0.3")

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
