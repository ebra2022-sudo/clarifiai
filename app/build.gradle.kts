import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// `clarity.baseUrl` / `clarity.devTier` come from -P flags, gradle.properties or local.properties (git-ignored).
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun prop(name: String): String? = (project.findProperty(name) as String?) ?: localProps.getProperty(name)

android {
    namespace = "com.clarifiai.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.clarifiai.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Backend base URL (must end with "/").
        buildConfigField("String", "BASE_URL", "\"${prop("clarity.baseUrl") ?: "https://clarifiai-api.onrender.com/"}\"")
        // Debug builds only: lets you test paid tiers without Google Play. Requires DEV_ALLOW_TIER_OVERRIDE=true
        // on the backend. One of "", "FREE", "PRO", "MAX".
        buildConfigField("String", "DEV_TIER", "\"\"")
    }

    buildTypes {
        debug {
            // Default 10.0.2.2 is the host machine from the Android emulator. For a phone, set
            // clarity.baseUrl=https://<your-service>.onrender.com/ in local.properties.
            buildConfigField("String", "BASE_URL", "\"${prop("clarity.baseUrl") ?: "http://10.0.2.2:8000/"}\"")
            buildConfigField("String", "DEV_TIER", "\"${prop("clarity.devTier") ?: ""}\"")
        }
        release {
            optimization {
                enable = true
            }
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.billing.ktx)
    implementation(libs.markdown.renderer.m3)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
