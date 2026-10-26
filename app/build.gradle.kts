plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.starwindow.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.starwindow.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 ist für Compose kein Größen-, sondern ein Laufzeitthema: Ohne die Optimierung läuft
            // die Oberfläche spürbar langsamer. Flüssigkeit deshalb nur an einem Release-Build beurteilen.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

baselineProfile {
    // Der Kaltstart-Teil des Profils ordnet zusätzlich die DEX-Dateien so, dass beim Start weniger
    // Code von der Platte gelesen werden muss.
    dexLayoutOptimization = true
}

// Umgeht einen Fehler des Baseline-Profile-Plugins (1.5.0, noch in 1.6.0-alpha01): Es kopiert die
// Kotlin-Quellordner von `release` als Text in seine eigenen Buildtypen. AGP 9 führt diese Ordner als
// Provider, und als Text wird daraus der Pfad „provider(?)" — unter Windows ein ungültiger Pfad, an
// dem `compileNonMinifiedReleaseKotlin` abbricht. Hier stehen die gemeinten Ordner ausgeschrieben.
// Kann weg, sobald `./gradlew :app:assembleNonMinifiedRelease` ohne diesen Block durchläuft.
androidComponents {
    finalizeDsl { android ->
        for (buildType in listOf("nonMinifiedRelease", "benchmarkRelease")) {
            android.sourceSets.findByName(buildType)?.kotlin {
                setSrcDirs(
                    listOf(
                        "src/$buildType/kotlin",
                        "src/$buildType/java",
                        "src/release/kotlin",
                        "src/release/java",
                    )
                )
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.kotlinx.serialization.json)

    // Spielt das Baseline Profile auch ein, wenn die App nicht aus dem Play Store kommt.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
