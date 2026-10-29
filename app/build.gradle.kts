import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing is read from keystore.properties (git-ignored) or environment variables.
// No credentials are stored in the build scripts.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    (keystoreProperties.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

/** Public (non-secret) build settings: Gradle property (-P or gradle.properties) or environment variable. */
fun publicSetting(name: String, default: String = ""): String =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "bd.egpmobile.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "bd.egpmobile.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Publisher details shown in the in-app policies. Supply real, verified values for release.
        buildConfigField("String", "DEVELOPER_NAME", quoted(publicSetting("EGP_DEVELOPER_NAME")))
        buildConfigField("String", "SUPPORT_EMAIL", quoted(publicSetting("EGP_SUPPORT_EMAIL")))
        buildConfigField("String", "PUBLISHER_WEBSITE", quoted(publicSetting("EGP_PUBLISHER_WEBSITE")))
        buildConfigField("String", "POLICY_EFFECTIVE_DATE", quoted(publicSetting("EGP_POLICY_EFFECTIVE_DATE")))
    }

    val releaseStoreFile = signingValue("storeFile", "EGP_KEYSTORE_FILE")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "EGP_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "EGP_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "EGP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Local backend on the emulator host by default (cleartext allowed only in debug for 10.0.2.2/localhost).
            buildConfigField("String", "API_BASE_URL", quoted(publicSetting("EGP_API_BASE_URL_DEBUG", "http://10.0.2.2:8080/")))
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted(publicSetting("EGP_GOOGLE_WEB_CLIENT_ID")))
            buildConfigField("String", "ENTITLEMENT_PUBLIC_KEY", quoted(publicSetting("EGP_ENTITLEMENT_PUBLIC_KEY")))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Must be HTTPS in release; an empty value makes the app report "service not configured".
            buildConfigField("String", "API_BASE_URL", quoted(publicSetting("EGP_API_BASE_URL")))
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted(publicSetting("EGP_GOOGLE_WEB_CLIENT_ID")))
            buildConfigField("String", "ENTITLEMENT_PUBLIC_KEY", quoted(publicSetting("EGP_ENTITLEMENT_PUBLIC_KEY")))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.webkit)

    // Account (Google sign-in), trial and PayStation checkout.
    implementation(libs.androidx.browser)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
}
