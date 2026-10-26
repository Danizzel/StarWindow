// Erzeugt das Baseline Profile der App und misst Start und Bildzeiten (siehe DEV_PLAN, Abschnitt 11).
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.starwindow.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // Baseline Profiles lassen sich erst ab Android 9 erzeugen, ohne Root erst ab Android 13.
        minSdk = 28
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

baselineProfile {
    // Ein echtes Gerät statt eines Emulators: Der Sucher braucht Kamera, Kompass und Kreisel, und ohne
    // sie liefe ein anderer Code als der, um den es geht.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
