plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Genre backend. Unset = the emulator's localhost, used by debug builds only:
// release builds call the backend only when EQIFY_BASE_URL is an https URL.
val eqifyBaseUrlProperty = providers.gradleProperty("EQIFY_BASE_URL").orNull?.trim().orEmpty()
val eqifyBaseUrl = eqifyBaseUrlProperty.ifEmpty { "http://10.0.2.2:3000/" }
val releaseBackendEnabled = eqifyBaseUrlProperty.startsWith("https://")
// Optional feedback address for Settings > Send feedback (blank = the user picks a recipient).
val feedbackEmail = providers.gradleProperty("EQIFY_FEEDBACK_EMAIL").orElse("").get().trim()
    .also { require(!it.contains('"') && !it.contains('\\')) { "EQIFY_FEEDBACK_EMAIL must be a plain address" } }
// Release signing, from the developer's ~/.gradle/gradle.properties (never commit these).
val releaseStoreFile = providers.gradleProperty("EQIFY_KEYSTORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("EQIFY_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("EQIFY_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("EQIFY_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }
val headphoneDataBaseUrl = providers.gradleProperty("EQIFY_HEADPHONE_DATA_BASE_URL")
    .orElse("https://parthsharma365628.github.io/eqify-data/")
    .get()
// Sentry DSN from the developer's ~/.gradle/gradle.properties; blank disables crash reporting.
val sentryDsn = providers.gradleProperty("EQIFY_SENTRY_DSN")
    .orElse("")
    .get()
    .trim()
    .also { require(!it.contains('"') && !it.contains('\\')) { "EQIFY_SENTRY_DSN must be a plain URL" } }

android {
    namespace = "com.example.eqify"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Permanent once published: stores identify the app by this ID.
        applicationId = "com.liteplane.eqify"
        minSdk = 26
        targetSdk = 36
        // Increase versionCode for every build you hand out.
        versionCode = 3
        versionName = "0.2.0-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    defaultConfig.buildConfigField(
        "String",
        "BASE_URL",
        34.toChar() + eqifyBaseUrl + 34.toChar()
    )
    defaultConfig.buildConfigField(
        "String",
        "HEADPHONE_DATA_BASE_URL",
        34.toChar() + headphoneDataBaseUrl + 34.toChar()
    )
    defaultConfig.buildConfigField(
        "String",
        "SENTRY_DSN",
        34.toChar() + sentryDsn + 34.toChar()
    )
    defaultConfig.buildConfigField(
        "String",
        "FEEDBACK_EMAIL",
        34.toChar() + feedbackEmail + 34.toChar()
    )

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "GENRE_BACKEND_ENABLED", "true")
            // Plain HTTP only for the local backend at 10.0.2.2 / your PC's IP.
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            buildConfigField("boolean", "GENRE_BACKEND_ENABLED", releaseBackendEnabled.toString())
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // QR generation only (pure Java, no camera/permissions) for profile sharing.
    implementation("com.google.zxing:core:3.5.3")
    // QR scanning via Google Play services' own camera UI: no CAMERA permission.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Play services pulls fragment 1.0.0, which breaks registerForActivityResult; force a modern one.
    implementation("androidx.fragment:fragment:1.8.5")
    // Crash/ANR/native-crash reporting; initialized manually in EqifyApplication.
    implementation("io.sentry:sentry-android:7.14.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Retrofit & Gson for API calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
// ViewModel for Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
}
