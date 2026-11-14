plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.ultrahdr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ultrahdr"
        minSdk = 34 // Android 14+ for native Gainmap & Ultra HDR support
        targetSdk = 35
        versionCode = 15
        versionName = "2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric needs merged resources and the manifest to render Compose screens on the JVM.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            // Screenshot tests write PNGs only when asked: gradle testDebugUnitTest -Pscreenshots
            if (project.hasProperty("screenshots")) {
                it.systemProperty("roborazzi.test.record", "true")
            }
            it.maxHeapSize = "2g"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Jetpack Compose BOM & UI
    // 2024.12.01 = Compose 1.7.6. 1.7 is the first version with GraphicsLayer, which the frosted
    // glass in ui/Glass.kt is built on.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Jetpack ExifInterface
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.exifinterface:exifinterface:1.3.7")

    // JVM tests that run Android's real graphics stack (decode, encode, gain maps) and
    // render the Compose screens to PNG, so the app can be checked without a phone.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.26.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
