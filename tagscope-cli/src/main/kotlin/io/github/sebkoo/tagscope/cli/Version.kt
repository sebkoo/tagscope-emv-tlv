package io.github.sebkoo.tagscope.cli

import java.util.Properties

/**
 * The version string shown by `--version`.
 *
 * Read from a resource the build generates from the Gradle project version, so the number has one
 * source of truth and cannot drift from the build. Falls back to `"unknown"` if the resource is
 * somehow absent, which `--version` then reports plainly rather than failing.
 */
internal fun tagscopeVersion(): String {
    val stream = VersionAnchor::class.java.getResourceAsStream("/$VERSION_RESOURCE") ?: return UNKNOWN
    return stream.use { input ->
        val properties = Properties()
        properties.load(input)
        properties.getProperty("version")?.takeIf { it.isNotBlank() } ?: UNKNOWN
    }
}

/** A stable class in this package to resolve the classpath resource against. */
private object VersionAnchor

private const val VERSION_RESOURCE: String = "tagscope-version.properties"
private const val UNKNOWN: String = "unknown"
