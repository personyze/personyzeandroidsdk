plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.serialization)
	`maven-publish`
	signing
}

group = "com.personyze"
version = libs.versions.personyze.get()

android {
	namespace = "com.personyze.android"
	compileSdk = 36

	defaultConfig {
		minSdk = 24
		consumerProguardFiles("consumer-rules.pro")
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		buildConfigField("String", "SDK_VERSION", "\"$version\"")
	}

	buildFeatures {
		buildConfig = true
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

	testOptions {
		unitTests {
			isIncludeAndroidResources = true
			isReturnDefaultValues = true
		}
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
	// In the public API: `StateFlow` and suspend functions.
	api(libs.coroutines.android)
	// In the public API: `PersonyzeConfig.okHttpClient`.
	api(libs.okhttp)
	implementation(libs.serialization.json)
	implementation(libs.androidx.core)
	implementation(libs.work.runtime)
	implementation(libs.webkit)
	implementation(libs.androidsvg)

	testImplementation(libs.junit)
	testImplementation(libs.coroutines.test)
	testImplementation(libs.mockwebserver)
	testImplementation(libs.robolectric)
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.work.testing)
	testImplementation(libs.turbine)

	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.espresso.web)
	androidTestImplementation(libs.coroutines.test)
}

// The live conformance test (SPEC section 9) runs only with a key; never put one in a file here.
tasks.withType<Test>().configureEach {
	providers.environmentVariable("PERSONYZE_API_KEY").orNull?.let { environment("PERSONYZE_API_KEY", it) }
	providers.environmentVariable("PERSONYZE_BASE_URL").orNull?.let { environment("PERSONYZE_BASE_URL", it) }
}

publishing {
	publications {
		register<MavenPublication>("release") {
			artifactId = "personyze-android"
			afterEvaluate {
				from(components["release"])
			}
			pom {
				name.set("Personyze Android SDK")
				description.set("Personalization and A/B testing for Android apps: Personyze decisions, events, rendered actions and notifications.")
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
