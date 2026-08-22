plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "space.pitchstone.tether.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            // Central rejects a publication without one.
            withJavadocJar()
        }
    }
}

// The classic DSL leaves Kotlin's JVM target at the toolchain default, which no longer matches
// `compileOptions` above; AGP's own consistency check fails the build unless they are pinned
// to the same level.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // `api`: WhatsAppManager appears in WhatsAppScreen's own signature, so consumers of the UI
    // artifact need the core one on their compile classpath anyway.
    api(project(":tether-core"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // QR rendering for the pairing screen.
    implementation(libs.zxing.core)
}

// Coordinates, POM, signing and repositories are shared with the other artifact; only these three
// differ. See gradle/publishing.gradle.kts.
extra["artifactName"] = "tether-ui"
extra["pomName"] = "Tether UI"
extra["pomDescription"] =
    "Optional Compose surface for Tether: QR pairing, connection status and an observed-message list."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
