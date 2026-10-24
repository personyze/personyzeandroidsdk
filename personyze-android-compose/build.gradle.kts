plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.compose)
	`maven-publish`
	signing
}

group = "com.personyze"
version = libs.versions.personyze.get()

android {
	namespace = "com.personyze.android.compose"
	compileSdk = 36

	defaultConfig {
		minSdk = 24
	}

	buildFeatures {
		compose = true
	}

	lint {
		// The versions are pinned on purpose (gradle/libs.versions.toml says why): newer androidx would
		// raise every app's compileSdk, newer Kotlin libraries would not compile with AGP 9.4's Kotlin.
		disable += setOf("GradleDependency", "NewerVersionAvailable")
		warningsAsErrors = true
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	publishing {
		singleVariant("release") {
			withSourcesJar()
			withJavadocJar()
		}
	}
}

kotlin {
	explicitApi()
}

dependencies {
	api(project(":personyze-android"))
	// An explicit version, not a BOM: a BOM in the published POM would pin the app's Compose.
	implementation(libs.compose.ui)
}

publishing {
	publications {
		register<MavenPublication>("release") {
			artifactId = "personyze-android-compose"
			afterEvaluate {
				from(components["release"])
			}
			pom {
				name.set("Personyze Android SDK - Compose")
				description.set("A Jetpack Compose composable that renders a Personyze action.")
				url.set("https://personyze.com/wiki/mob:android-sdk-guide")
				licenses {
					license {
						name.set("MIT")
						url.set("https://opensource.org/licenses/MIT")
					}
				}
				developers {
					developer {
						id.set("personyze")
						name.set("Personyze")
						email.set("support@personyze.com")
					}
				}
				scm {
					connection.set("scm:git:https://github.com/personyze/personyzeandroidsdk.git")
					developerConnection.set("scm:git:ssh://git@github.com/personyze/personyzeandroidsdk.git")
					url.set("https://github.com/personyze/personyzeandroidsdk")
				}
			}
		}
	}
	repositories {
		maven {
			name = "build"
			url = uri(rootProject.layout.buildDirectory.dir("repo"))
		}
	}
}

/*	Maven Central wants every file signed. Only when asked (-Ppersonyze.sign, as `centralBundle` is run), with the key
	in the publisher's own gpg (`signing.gnupg.keyName` in ~/.gradle/gradle.properties): the plain publish to build/repo
	that the test app's Maven mode reads needs no key. */
if (providers.gradleProperty("personyze.sign").isPresent)
{	signing {
		useGpgCmd()
		sign(publishing.publications["release"])
	}
}
