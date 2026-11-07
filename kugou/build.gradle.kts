// Grocool's Kotlin port of the KuGouMusicApi core.
//
// An android library, matching the tree's native module shape: the chassis'
// other modules are all Android-flavoured (app/tv/wearos are AGP modules,
// :shared/:sharedUi are KMP), and its AGP+Kotlin toolchain resolved a plain
// Kotlin/JVM module's members as stubs (class names visible, signatures
// gone - seen live across four CI rounds: an import of KugouClient type
// checked, while entry.key on a Map from asCookieMap() did not). Sources and
// tests keep the plain src/main(kotlin)/src/test(kotlin) layout the Kotlin
// Android plugin expects. The real-backend smoke already served its purpose
// from a portable JDK; its task is gone, the program stays as documentation.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.grocool.kugou"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Same Ktor/serialization line as the BitChord chassis (app module) so the
    // versions stay in lockstep when the KMP tree is upgraded.
    implementation("io.ktor:ktor-client-core:3.5.2")
    // OkHttp engine over CIO: h2 support and a mainstream TLS fingerprint —
    // the lyric host serves empty bodies to JVM CIO clients.
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // QR rendering for the login smoke; same artifact the chassis app uses.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(kotlin("test"))
}

/** Prints the runtime classpath for local tooling. */
tasks.register("printClasspath") {
    doLast { println(configurations.getByName("prodDebugRuntimeClasspath").asPath) }
}
