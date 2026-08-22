plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.wire)
    alias(libs.plugins.ksp)
    `maven-publish`
}

android {
    namespace = "space.pitchstone.tether"
    compileSdk = 37

    defaultConfig {
        // Deliberately below the sample app's minSdk: a library should not force its floor
        // onto consumers, and nothing here needs anything newer.
        minSdk = 26
        // Shipped inside the AAR and applied to any consumer that minifies. libsignal, Wire and
        // BouncyCastle all resolve things reflectively, so without these a release build fails at
        // runtime rather than at compile time.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
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

// Generate Kotlin from the vendored WAProto schema (src/main/proto/WAProto.proto).
wire {
    kotlin {}
}

dependencies {
    // `api`, not `implementation`: these types appear in the module's own public API, so they
    // must be on a consumer's compile classpath. WhatsAppManager exposes StateFlow<State> and
    // takes a suspend callback; without this a consumer cannot even reference them.
    api(libs.kotlinx.coroutines.core)

    // NotificationCompat, for the foreground service that keeps the connection alive.
    implementation(libs.androidx.core.ktx)

    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(libs.curve25519)
    implementation(libs.signal.protocol)
    implementation(libs.wire.runtime)

    // Session/persistence (WhatsAppManager, credential + Signal-store persistence)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Wrapped in afterEvaluate because the Android `release` software component only exists once
// AGP has finished configuring its variants.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "space.pitchstone"
                artifactId = "tether-core"
                version = providers.gradleProperty("tether.version").get()

                pom {
                    name.set("Tether Core")
                    description.set("Headless WhatsApp multi-device companion client for Android: QR pairing, Noise transport, Signal decryption and message observing.")
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
