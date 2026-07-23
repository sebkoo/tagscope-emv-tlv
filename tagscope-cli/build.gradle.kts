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
    // Arrives at commit 9; the build does not require the class to exist yet.
    mainClass = "io.github.sebkoo.tagscope.cli.MainKt"
    applicationName = "tagscope"
}

tasks.test {
    useJUnitPlatform()
}
