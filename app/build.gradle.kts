plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version: release builds pass -PappVersionName=1.2.3 (taken from the git tag v1.2.3).
val appVersionName: String = (findProperty("appVersionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.0"
val appVersionCode: Int = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: run {
    val parts = appVersionName.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    parts[0] * 10000 + parts[1] * 100 + parts[2]
}

// Release signing key, supplied by CI secrets or your shell:
// SIGNING_KEYSTORE_FILE, SIGNING_KEYSTORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD.
// Without them, release builds fall back to the debug key (fine for personal installs only).
val releaseKeystore: File? = System.getenv("SIGNING_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.pdfviewer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pdfviewer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    // One small APK per CPU type (a phone only needs one), plus a universal APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
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
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Optional mirror for Robolectric's runtime jars: ./gradlew test -ProboRepo=https://...
            (project.findProperty("roboRepo") as String?)?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
            )
        }
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    // Rendering, text, search, links, outline (pdfium)
    implementation("io.legere:pdfiumandroid:1.0.35")
    // Editing: annotations, forms, pages, merge/split, encryption
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
