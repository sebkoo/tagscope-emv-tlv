import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
}

kotlin {
    // Every public declaration needs an explicit visibility modifier and return type.
    explicitApi()
    jvmToolchain(21)
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Gradle 9 no longer injects the launcher implicitly; without this every test task
    // fails with "Failed to load JUnit Platform".
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Publishing to Maven Central via the Central Portal (central.sonatype.com). The Vanniktech
// plugin is build-time only; it does not ship in the artifact, so the library keeps zero runtime
// dependencies. Only this module is published — the coordinate is io.github.sebkoo:tagscope even
// though the module directory is tagscope-lib.
//
// Credentials and the GPG signing key are NEVER stored in the repository. Set them in
// ~/.gradle/gradle.properties (user-level) or as environment variables:
//   mavenCentralUsername          Central Portal user token (name)
//   mavenCentralPassword          Central Portal user token (secret)
//   signingInMemoryKey            ASCII-armored GPG secret key
//   signingInMemoryKeyId          short key id (only if the keyring holds more than one key)
//   signingInMemoryKeyPassword    GPG key passphrase
// Signing engages automatically once the key is present; its absence does not block a local
// publishToMavenLocal used for verification.
mavenPublishing {
    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
        ),
    )

    publishToMavenCentral()
    signAllPublications()

    coordinates("io.github.sebkoo", "tagscope", project.version.toString())

    pom {
        name.set("Tagscope")
        description.set("An EMV BER-TLV parser and tag decoder for the JVM.")
        inceptionYear.set("2026")
        url.set("https://github.com/sebkoo/tagscope-emv-tlv")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("sebkoo")
                name.set("Ben Koo")
                url.set("https://github.com/sebkoo")
            }
        }

        scm {
            url.set("https://github.com/sebkoo/tagscope-emv-tlv")
            connection.set("scm:git:git://github.com/sebkoo/tagscope-emv-tlv.git")
            developerConnection.set("scm:git:ssh://git@github.com/sebkoo/tagscope-emv-tlv.git")
        }
    }
}
