plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    application
}

kotlin {
    // No explicitApi() here: on an application module it only forces `public fun main`.
    jvmToolchain(21)
}

dependencies {
    implementation(project(":tagscope-lib"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "io.github.sebkoo.tagscope.cli.MainKt"
    applicationName = "tagscope"
}

// The version --version prints comes from the Gradle project version, written to a resource at
// build time, so it has one source of truth and cannot drift from the build. Captured into a local
// here rather than read inside doLast, so the task stays configuration-cache friendly.
val projectVersion = version.toString()
val generateVersionProperties by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/version")
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("tagscope-version.properties").asFile
        file.writeText("version=$projectVersion\n")
    }
}

sourceSets["main"].resources.srcDir(generateVersionProperties)

tasks.test {
    useJUnitPlatform()
}
