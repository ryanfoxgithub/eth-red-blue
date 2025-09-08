// This is the Gradle build file for the *app module* (Kotlin DSL).
// I use Gradle's Version Catalog (libs.versions.toml) so most versions
// are defined in one place and referenced here via `libs.*`.

plugins {
    // Android application plugin (required to build an .apk)
    alias(libs.plugins.android.application)
    // Kotlin Android plugin so I can write Android code in Kotlin
    alias(libs.plugins.kotlin.android)
}

android {
    // The package namespace used by R, BuildConfig, etc.
    namespace = "au.edu.deakin.lab.lockersim"

    // I compile against Android 16 (API 36). This lets me use new APIs.
    // It does NOT force users to be on 16; that's controlled by minSdk.
    compileSdk = 36

    defaultConfig {
        // The unique application id that becomes the install package name.
        applicationId = "au.edu.deakin.lab.lockersim"

        // I only support devices on Android 14 (API 34) or newer.
        // If I lowered this, more devices could install; if I raised it,
        // some devices would be blocked from installing.
        minSdk = 34

        // I test and declare compatibility with Android 16 features/behavior.
        targetSdk = 36

        // Internal versioning for Play/Package Manager.
        versionCode = 1        // increment for each release build
        versionName = "1.0"    // human‑readable version

        // Instrumentation runner used by androidTest sources.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        // I use ViewBinding to safely access views without findViewById.
        viewBinding = true
    }

    buildTypes {
        // Release build configuration (debug is auto‑generated)
        release {
            // I keep code shrinking/obfuscation OFF for easier debugging
            // and to simplify marking. To shrink, set this to true.
            isMinifyEnabled = false

            // If I enable minify, these are the ProGuard/R8 rules to use.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Java language level. I compile source to Java 17 bytecode.
    // If I used older toolchains or libraries, I could drop to 11/8,
    // but WorkManager/OkHttp/Kotlin all work well on 17.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin JVM target to match the Java level above.
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ---- AndroidX + UI basics ----
    implementation(libs.androidx.core.ktx)          // Kotlin extensions for core Android APIs
    implementation(libs.androidx.appcompat)         // Backwards‑compatible widgets (AppCompatActivity)
    implementation(libs.material)                   // Material Components (buttons, theming)
    implementation(libs.androidx.activity)          // Activity/ActivityResult APIs
    implementation(libs.androidx.constraintlayout)  // (Optional) If I use ConstraintLayout in XML

    // ---- Background work + networking ----
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    // WorkManager lets me enqueue background tasks reliably (e.g., beacons).
    // If I bumped this version, I’d also re-sync Gradle and test constraints/backoff.

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // OkHttp is the HTTP client I use for POSTs. If I swapped this out,
    // I’d change imports and request-building code.

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Coroutines give me structured concurrency on Android (suspend functions
    // in WorkManager, etc.). If I update the version, I make sure Kotlin stdlib
    // in the catalog is compatible.

    implementation("androidx.documentfile:documentfile:1.0.1")
    // DocumentFile wraps the Storage Access Framework so I can read/write files
    // in a user‑granted tree (e.g., DCIM) without raw file paths.

    // ---- Test deps ----
    testImplementation(libs.junit)                   // Local unit tests (JVM)
    androidTestImplementation(libs.androidx.junit)   // Instrumented tests (Android)
    androidTestImplementation(libs.androidx.espresso.core) // UI testing
}
