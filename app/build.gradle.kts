plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseVersionCode = providers.gradleProperty("APP_VERSION_CODE")
    .orNull
    ?.toIntOrNull()
    ?: 1

val releaseVersionName = providers.gradleProperty("APP_VERSION_NAME")
    .orNull
    ?: "0.1.0"

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.ares.proxmoxmobilemanager"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ares.proxmoxmobilemanager"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("proxmox-release.jks")
            storeFile = keystoreFile
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: System.getenv("KEY_ALIAS") ?: "proxmox-release"
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD") ?: ""
            storeType = "PKCS12"
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



    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    // Required by AndroidX Startup: EmojiCompat references ProcessLifecycleInitializer at app launch.
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.connectbot:termlib:0.3.13") {
        exclude(group = "androidx.core")
        exclude(group = "androidx.compose")
        exclude(group = "androidx.activity")
        exclude(group = "androidx.lifecycle")
        exclude(group = "androidx.appcompat")
        exclude(group = "com.google.android.material")
    }
    debugImplementation("androidx.compose.ui:ui-tooling")
}
