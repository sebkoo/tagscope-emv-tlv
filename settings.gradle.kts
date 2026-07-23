plugins {
    // Resolves the Java 21 toolchain from a remote distribution when no local JDK 21 is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "tagscope"

// Declared once here rather than per module, so a module cannot quietly add its own source.
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(":tagscope-lib", ":tagscope-cli")
