import java.io.StringReader
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Signing credentials live in keystore.properties (untracked, see .gitignore). The contents are read
// through a provider so the configuration cache tracks them as an input: adding, editing or removing
// the file invalidates the cached configuration instead of silently reusing an earlier signing
// decision - which is how a CI release once ended up debug-signed even though the keystore was there.
val keystoreText = providers
    .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
    .asText
    .orNull
val keystoreProperties = Properties()
keystoreText?.let { keystoreProperties.load(StringReader(it)) }
val releaseStoreFile = rootProject.file(
    keystoreProperties.getProperty("storeFile") ?: "multiroute-release.jks"
)
// The keystore binary itself can only be probed for existence here; in practice adding
// keystore.properties is what enables signing, and that file is tracked by the provider above.
val hasReleaseKeystore = keystoreText != null && releaseStoreFile.exists()

/** Most recent git tag reachable from HEAD, or [fallback] when the checkout carries no tags. */
fun latestGitTag(fallback: String): String {
    val described = try {
        providers.exec {
            commandLine("git", "describe", "--tags", "--abbrev=0")
            isIgnoreExitValue = true
        }.standardOutput.asText.getOrElse("").trim()
    } catch (_: Throwable) {
        ""
    }
    return described.ifEmpty { fallback }
}

/**
 * The version name a release tag refers to. Both the plain `v1.2.3` form and the
 * `<versionCode>-<versionName>` form that the Xposed module repository indexes are accepted, so the
 * same tag can be used here and there.
 */
fun versionNameFromTag(tag: String): String =
    tag.substringAfter('-', tag).let { if (it == tag) tag.removePrefix("v") else it }

/**
 * Commit count of the upstream branch, falling back to the local HEAD and finally to 1 when git
 * metadata is unavailable (a source archive, for example).
 *
 * The higher of the two is used: a clone, CI and a fork at the upstream tip therefore agree, while a
 * commit that has not been pushed yet is already reflected locally instead of lagging one behind.
 */
fun upstreamCommitCount(): Int {
    val counts = listOf("origin/master", "HEAD").mapNotNull { ref ->
        try {
            providers.exec {
                commandLine("git", "rev-list", "--count", ref)
                isIgnoreExitValue = true
            }.standardOutput.asText.getOrElse("").trim().toIntOrNull()?.takeIf { it > 0 }
        } catch (_: Throwable) {
            null
        }
    }
    return counts.maxOrNull() ?: 1
}

fun isWorkTreeDirty(): Boolean = try {
    providers.exec {
        commandLine("git", "status", "--porcelain")
        isIgnoreExitValue = true
    }.standardOutput.asText.getOrElse("").trim().isNotEmpty()
} catch (_: Throwable) {
    false
}

/**
 * Version identity, using the scheme LSPosed applies to its own releases: the code is the upstream
 * commit count plus a fixed offset, and the name comes from the latest tag.
 *
 * - Counting `origin/master` instead of the local HEAD means CI, a fresh clone and a fork that added
 *   its own commits all produce the same code for the same upstream state, which is what makes a
 *   self-built APK comparable with an official one.
 * - The offset keeps the code above every number this project has shipped under any earlier scheme, so
 *   an update is never mistaken for a downgrade.
 * - A working tree with uncommitted changes is named `-local`, and both fields can be overridden for
 *   builds that need their own numbering:
 *       ./gradlew assembleRelease -PmultiRouteVersionName=1.0.0-fork -PmultiRouteVersionCode=19999
 *
 * The exact revision is additionally identified by `HOOK_BUILD_ID` below.
 */
val versionCodeOffset = 10000
val fallbackVersionName = "1.0.0"
val releaseVersionName = versionNameFromTag(latestGitTag(fallbackVersionName))
val appVersionName = (findProperty("multiRouteVersionName") as String?)?.takeIf { it.isNotBlank() }
    ?: if (isWorkTreeDirty()) "$releaseVersionName-local" else releaseVersionName
val appVersionCode = (findProperty("multiRouteVersionCode") as String?)?.toIntOrNull()
    ?: (versionCodeOffset + upstreamCommitCount())

/**
 * Short source fingerprint of this build: `<git-sha>[-dirty]`.
 *
 * Used to detect that system_server still executes an older module build: a System Framework scoped
 * module cannot be hot-reloaded, so after installing an APK the running hook code may silently lag
 * behind. Unlike the version fields this reflects the exact revision, including uncommitted changes.
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
        // Android 11 is the real floor: the interfaces the module hooks (getMobileDataPreferredUids and
        // friends) do not exist below it, so installing on an older release could only ever fail.
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // Baked into BuildConfig so the hook code can publish the identity of the code that is actually
        // loaded inside system_server. Unlike versionCode this also changes for local uncommitted builds,
        // which is what makes "an update has not been applied yet" detectable during development.
        buildConfigField("String", "HOOK_BUILD_ID", "\"${getGitBuildId()}\"")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 is required, not merely nice to have: without it the dex is ~42 MB, which APK
            // compression used to hide - but once minSdk reaches 30 the dex is stored uncompressed and
            // the download grew from 12 MB to 43 MB. The keep rules for the hook entry point, the
            // provider, the models and LibXposed live in proguard-rules.pro.
            isMinifyEnabled = true
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
