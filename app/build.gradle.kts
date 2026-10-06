plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.devtools.ksp")
    // Required by Hilt: it rewrites @AndroidEntryPoint bytecode so the generated base classes are
    // used at runtime. Without it the app compiles but crashes on launch.
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.meditrack"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.meditrack"
        minSdk = 26          // Android 8.0 Oreo
        targetSdk = 34       // Android 14
        // Bumped per delivery so each build can install straight over the last one (both are signed
        // with the same key, so a higher versionCode is what makes that an upgrade rather than a
        // downgrade refusal).
        //  13 / 2.1.0 - in-app update download + install with mirror choice and release notes, trimmer
        //               fixed, terminology pass, dropdown selectors, app-lock row opens the system
        //               setting, developer options with debug and time-boxed demo mode
        //  12 / 2.0.0 - custom ringtone with in-app trimming, ringing until acknowledged, per-medication
        //               review reminders («复查提醒»), offline knowledge base, first-run agreement,
        //               expandable settings, cache clearing
        //  11 / 1.9.0 - five bundled reminder tones (own audio, no system ringtone), tone-versioned channel
        //  10 / 1.8.1 - update check: own-site manifest first, three endpoints, real failure reasons
        //   9 / 1.8.0 - automatic update check (the app's only network call; prompt, never forced)
        //   8 / 1.7.0 - pickable backup folder, in-app backup list (newest first), automatic migration
        //   7 / 1.6.0 - do-not-disturb window («免打扰时段»), silence and deferral decided separately
        //   6 / 1.5.0 - unlock catch-up («解锁补提醒»), legacy idle deferral retired, capped font scale
        //   5 / 1.4.0 - one resizable widget, no stepper buttons, 10s-10min refresh, colour ordering
        //   4 / 1.3.0 - background guard service, alarm-clock alarms and lost-alarm evidence
        //   3 / 1.2.0 - widget size family (4x1 / 4x2 / 4x3 / 4x4)
        //   2 / 1.1.0 - reminder pipeline rewrite
        versionCode = 13
        versionName = "2.1.0"

        // Custom runner that swaps in HiltTestApplication for the instrumented tests.
        testInstrumentationRunner = "com.meditrack.MediTrackTestRunner"
        vectorDrawables { useSupportLibrary = true }

        // Export the Room schema so migrations are reviewable in code review.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    buildTypes {
        debug {
            // NOTE: no applicationIdSuffix on purpose.
            //
            // The original ".debug" suffix meant the debug build could not be installed over the
            // release APK, so a device running the release build could not be inspected at all
            // (release is not debuggable, so `adb shell run-as` refuses to open its database).
            // Sharing the release applicationId - and the same debug signing key the release build
            // already uses - lets a debug build *replace* it in place, preserving the user's data.
            // That is what makes on-device diagnosis possible.
            isMinifyEnabled = false
            versionNameSuffix = "-diag"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the debug key so `assembleRelease` works out of the box.
            // Replace with a real signingConfig before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Backport of java.time to API 26.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.glance.ExperimentalGlanceApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        // Must match Kotlin 1.9.22.
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The exported Room schemas are read by the instrumented migration tests, which need them on the
    // *asset* path of the test APK. Without this the schemas are only compile-time documentation.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    // ---------- Core / lifecycle ----------
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // ---------- Compose (BOM keeps every artifact on one version) ----------
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ---------- Room ----------
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ---------- DataStore ----------
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // ---------- Hilt ----------
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    kapt("androidx.hilt:hilt-compiler:1.1.0")

    // ---------- WorkManager ----------
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ---------- Glance (home-screen widget) ----------
    implementation("androidx.glance:glance-appwidget:1.0.0")
    implementation("androidx.glance:glance-material3:1.0.0")

    // ---------- Biometric app lock ----------
    implementation("androidx.biometric:biometric:1.1.0")

    // ---------- Audio: custom ringtone trimming ----------
    // The in-app trimmer has to decode an arbitrary user file (MP3 / M4A / AAC / WAV / FLAC) to
    // samples so it can draw a waveform, then re-encode the chosen slice into a small file. Hand-rolling
    // that on top of MediaCodec is where this gets dangerous: the vendor codecs differ per phone, and a
    // mis-negotiated output format fails only on the user's device. Media3 owns that negotiation.
    //
    // Pinned to 1.3.1 deliberately: it is the last Media3 line built against Kotlin 1.9 metadata, and
    // this module still compiles with Kotlin 1.9.22.
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")

    // ---------- Coroutines ----------
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ---------- JSON backup ----------
    // Gson is used only for the export/import format. Hand-rolled JSON would be a liability here:
    // a malformed backup file is a data-loss bug, and string escaping is exactly the kind of thing
    // a library gets right and a hand-written builder gets subtly wrong.
    implementation("com.google.code.gson:gson:2.10.1")

    // ---------- Desugaring for java.time on API 26 ----------
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // ---------- Unit tests ----------
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("com.google.truth:truth:1.1.5")
    // Reads the checked-in Room schema JSON in the migration test, so the hand-written v4 -> v5 DDL is
    // asserted against the schema Room actually generated rather than against a copy of itself.
    testImplementation("com.google.code.gson:gson:2.10.1")

    // ---------- Instrumented tests ----------
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // The same assertion library the JVM tests use, so an instrumented test reads like the unit tests
    // beside it rather than switching to a raw `assertEquals` dialect.
    androidTestImplementation("com.google.truth:truth:1.1.5")

    // Hilt test support: @HiltAndroidTest + HiltAndroidRule + the generated test component.
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.50")
    kaptAndroidTest("com.google.dagger:hilt-android-compiler:2.50")

    // MigrationTestHelper: proves the v1 -> v2 migration preserves real user data.
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
