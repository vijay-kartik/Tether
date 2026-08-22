// Shared publishing setup for :tether-core and :tether-ui.
//
// Applied from each module's build file, which supplies the three things that actually differ
// (artifact id, name, description) via `extra`. Everything else — coordinates, POM, signing, and
// the repositories — is identical, and was previously copied into both files: two artifacts that
// must agree on their licence and version are exactly the wrong thing to maintain twice.

apply(plugin = "signing")

val artifactName: String by extra
val pomName: String by extra
val pomDescription: String by extra

/** A gradle property if set, else the environment — CI supplies these as secrets. */
fun secret(property: String, environment: String): String? =
    providers.gradleProperty(property).orNull ?: System.getenv(environment)

afterEvaluate {
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "space.pitchstone"
                artifactId = artifactName
                version = providers.gradleProperty("tether.version").get()

                pom {
                    name.set(pomName)
                    description.set(pomDescription)
                    url.set("https://github.com/vijay-kartik/Tether")
                    // GPLv3, matching the libsignal/curve25519 libraries this links: their terms
                    // reach any app built against Tether, so advertising anything more permissive
                    // here would misstate a consumer's obligations. See NOTICE.
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
                    username = secret("gpr.user", "GITHUB_ACTOR")
                    password = secret("gpr.key", "GITHUB_TOKEN")
                }
            }
            maven {
                // Maven Central via the Central Portal's OSSRH-compatible staging API, which is
                // the documented route for plain maven-publish. A deploy lands in a staging
                // repository and is then released from the Portal; it does not go live on upload.
                name = "CentralPortal"
                url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                credentials {
                    username = secret("centralUsername", "CENTRAL_USERNAME")
                    password = secret("centralPassword", "CENTRAL_PASSWORD")
                }
            }
        }
    }

    configure<SigningExtension> {
        val key = secret("signingInMemoryKey", "SIGNING_KEY")
        val password = secret("signingInMemoryKeyPassword", "SIGNING_PASSWORD")
        // Central rejects unsigned artifacts, but a local publishToMavenLocal or a GitHub Packages
        // release needs no key — so signing turns itself on only when one is actually available,
        // rather than failing every build on a machine that has none.
        isRequired = key != null
        if (key != null) {
            useInMemoryPgpKeys(key, password)
            sign(extensions.getByType<PublishingExtension>().publications["release"])
        }
    }
}
