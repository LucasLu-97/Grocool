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
    // OkHttp engine over CIO: h2 support and a mainstream TLS fingerprint -
    // the lyric host serves empty bodies to JVM CIO clients.
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // QR rendering for the login smoke; same artifact the chassis app uses.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(kotlin("test"))
}

/**
 * Interactive end-to-end smoke against the real KuGou Concept backend:
 * registerDevice -> QR login -> token refresh -> search -> privilege -> stream
 * -> KRC lyrics. Not wired into any CI gate; run locally with :kugou:smoke.
 */
tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Real-backend smoke: device register, QR login, play URL chain"
    mainClass.set("com.grocool.kugou.smoke.SmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

/** Prints the runtime classpath so the smoke can also run as a bare `java -cp`. */
tasks.register("printClasspath") {
    doLast { println(sourceSets["main"].runtimeClasspath.asPath) }
}
