plugins {
    alias(libs.plugins.android.application)
}

// Deliberately dependency-free (no Compose, no AndroidX): it only has to run on a device as an ordinary
// app so MultiRoute's hook-visible state and the fate of its traffic can be observed from inside it.
android {
    namespace = "com.multiroute.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.multiroute.probe"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}
