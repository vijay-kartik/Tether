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
        singleVariant("release") { withSourcesJar() }
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

// Wrapped in afterEvaluate because the Android `release` software component only exists once
// AGP has finished configuring its variants.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "space.pitchstone"
                artifactId = "tether-ui"
                version = providers.gradleProperty("tether.version").get()

                pom {
                    name.set("Tether UI")
                    description.set("Optional Compose surface for Tether: QR pairing, connection status and an observed-message list.")
                    url.set("https://github.com/vijay-kartik/Tether")
                    // GPLv3, matching the libsignal/curve25519 libraries this links: their
                    // terms reach any app built against Tether, so advertising anything more
                    // permissive here would misstate a consumer's obligations. See NOTICE.
                    licenses {
                        license {
                            name.set("GNU General Public License, Version 3")
                            url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("vijay-kartik")
                            name.set("Kartik Vijayvergiya")
                        }
                    }
                    scm {
                        url.set("https://github.com/vijay-kartik/Tether")
                        connection.set("scm:git:https://github.com/vijay-kartik/Tether.git")
                        developerConnection.set("scm:git:ssh://git@github.com/vijay-kartik/Tether.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/vijay-kartik/Tether")
                credentials {
                    username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                    password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
