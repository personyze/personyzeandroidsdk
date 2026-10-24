pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		google()
		mavenCentral()
	}
}

rootProject.name = "personyze-android-sdk"

// Named after their artifactIds, so a composite build substitutes `com.personyze:personyze-android`
// with the project of the same name.
include(":personyze-android", ":personyze-android-compose")
