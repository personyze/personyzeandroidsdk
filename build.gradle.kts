// No `org.jetbrains.kotlin.android`: AGP 9 compiles Kotlin itself and refuses that plugin.
plugins {
	alias(libs.plugins.android.library) apply false
	alias(libs.plugins.kotlin.compose) apply false
	alias(libs.plugins.kotlin.serialization) apply false
}


/*	The upload for Maven Central (central.sonatype.com > Publish Component): both modules' signed files in the repository
	layout, this version only, without maven-metadata.xml - a bundle is one release, not a repository's history.

		./gradlew clean centralBundle -Ppersonyze.sign

	Refuses to build unsigned: the Portal would only reject it after the upload. */
val bundleVersion = libs.versions.personyze.get()
val bundleSigned = providers.gradleProperty("personyze.sign").isPresent
tasks.register<Zip>("centralBundle") {
	dependsOn(":personyze-android:publishReleasePublicationToBuildRepository", ":personyze-android-compose:publishReleasePublicationToBuildRepository")
	from(layout.buildDirectory.dir("repo")) {
		include("com/personyze/*/$bundleVersion/**")
		exclude("**/maven-metadata*")
	}
	archiveFileName.set("personyze-android-$bundleVersion-bundle.zip")
	destinationDirectory.set(layout.buildDirectory.dir("central"))
	// Never up to date: the checks below would be skipped with it.
	outputs.upToDateWhen { false }
	val repo = layout.buildDirectory.dir("repo")
	doFirst {
		if (!bundleSigned)
		{	throw GradleException("centralBundle: run it with -Ppersonyze.sign - Maven Central refuses unsigned files")
		}
		// Every file signed. The publish tasks this depends on sign each file again in this run, so an earlier build's
		// signature cannot stand in (their copy order makes a signature's time no guide to that).
		val checksum = Regex("\\.(asc|md5|sha1|sha256|sha512)$")
		val unsigned = repo.get().asFile.walk()
			.filter { it.isFile && it.path.contains("/$bundleVersion/") && !it.name.startsWith("maven-metadata") && !checksum.containsMatchIn(it.name) }
			.filter { !File(it.path + ".asc").exists() }
			.map { it.name }
			.toList()
		if (unsigned.isNotEmpty())
		{	throw GradleException("centralBundle: not signed: $unsigned")
		}
	}
}

// The modules' `clean` leaves the root build directory, and build/repo with it - old signatures included.
tasks.register<Delete>("clean") {
	delete(layout.buildDirectory)
}
