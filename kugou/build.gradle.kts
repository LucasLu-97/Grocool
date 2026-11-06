// Grocool's pure-JVM Kotlin port of the KuGouMusicApi core.
// Zero Android dependencies on purpose: the desktop targets reuse this
// module verbatim later, and unit tests run on any JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Same Ktor/serialization line as the BitChord chassis (app module) so the
    // versions stay in lockstep when the KMP tree is upgraded.
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-cio:3.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
}
