plugins {
    // Sorgt dafuer, dass Gradle das benoetigte JDK 25 automatisch herunterlaedt,
    // falls es lokal nicht installiert ist (paper-api 26.2 ist Java-25-Bytecode).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "NexoDB"
