import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
    val storeFilePath = keystoreProperties.getProperty("storeFile") ?: "multiroute-release.jks"
    val storeFile = rootProject.file(storeFilePath)
    storeFile.exists()
} else {
    false
}

fun getGitCommitCount(): Int {
    return try {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { text ->
            val count = text.trim().toIntOrNull()
            if (count != null && count > 0) count else 1
        }.getOrElse(1)
    } catch (_: Throwable) {
        1
    }
}

/**
 * Short source fingerprint of this build: `<git-sha>[-dirty]`.
 *
 * Used to detect that system_server still executes an older module build: a System Framework scoped
 * module cannot be hot-reloaded, so after installing an APK the running hook code may silently lag
 * behind. `versionCode` (commit count) cannot see uncommitted local changes, hence the separate field.
 */
fun getGitBuildId(): String {
    val sha = try {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.getOrElse("").trim()
    } catch (_: Throwable) {
        ""
    }
    val dirty = try {
        providers.exec {
            commandLine("git", "status", "--porcelain")
            isIgnoreExitValue = true
        }.standardOutput.asText.getOrElse("").trim().isNotEmpty()
    } catch (_: Throwable) {
        false
    }
    return sha.ifEmpty { "unknown" } + if (dirty) "-dirty" else ""
}

android {
    namespace = "com.multiroute"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.multiroute"
        minSdk = 24
        targetSdk = 36
        versionCode = getGitCommitCount()
        versionName = "1.0.0"
        // Baked into BuildConfig so the hook code can publish the identity of the code that is actually
        // loaded inside system_server. Unlike versionCode this also changes for local uncommitted builds,
        // which is what makes "an update has not been applied yet" detectable during development.
        buildConfigField("String", "HOOK_BUILD_ID", "\"${getGitBuildId()}\"")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile") ?: "multiroute-release.jks")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
                logger.warn("Warning: Release keystore not found or not configured! Falling back to debug signing config.")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      // Enabled so the hook code can publish the version it was compiled from (BuildConfig.VERSION_CODE),
      // which is what lets the app detect that system_server still runs an older module build.
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Modern LibXposed API
  compileOnly(libs.libxposed.api)
}
