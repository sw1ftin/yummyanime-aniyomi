plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "eu.kanade.tachiyomi.animeextension.ru.yummyanime"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.animeextension.ru.yummyanime"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "14.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}

dependencies {
    compileOnly("androidx.preference:preference:1.2.1")
    compileOnly("com.github.aniyomiorg:extensions-lib:14")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22")
    compileOnly("uy.kohesive.injekt:injekt-core:1.16.1")
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("org.jsoup:jsoup:1.16.1")
    compileOnly("com.squareup.okhttp3:okhttp:5.0.0-alpha.11")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.5.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    compileOnly("app.cash.quickjs:quickjs-android:0.9.2")
}
