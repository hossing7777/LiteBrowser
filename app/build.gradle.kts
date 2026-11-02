plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.litebrowser"
    compileSdk = 34

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "**/libpr-cli.so", "**/libbusybox.so",
                "**/libproot.so", "**/libproot-loader.so",
                "**/libtalloc.so" // ← اضافه شدن libtalloc
            )
        }
    }
    defaultConfig {
        applicationId = "com.litebrowser"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    androidResources {
        noCompress += listOf("pr-cli", "busybox", "libproot.so", "libproot-loader.so", "libtalloc.so")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug") // installable release; replace for Play Store
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":terminal-view"))
    implementation(project(":proot-engine"))
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("androidx.core:core-ktx:1.12.0")
}
