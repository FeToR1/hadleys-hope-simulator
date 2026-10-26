plugins {
    // Provisions the JDK 17 toolchain the build pins when no local installation matches.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "colony-dsl-parser"
