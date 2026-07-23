// Plugin versions are declared once here and applied per module, so no cross-project
// configuration block is needed. Coordinates (group, version) come from gradle.properties.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
}
