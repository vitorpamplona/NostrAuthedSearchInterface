plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.vitorpamplona.searchrelay"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.3"

dependencies {
    // WebSocket server (Netty engine scales to many concurrent connections)
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")

    // Non-blocking HTTP client for the search backend (CIO engine + connection pooling)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Schnorr (BIP-340) signature verification over secp256k1 — the same native
    // library Quartz (Amethyst) uses for Nostr events. The `-kmp` module carries the
    // Kotlin API; the `-jni-jvm` module carries the native binaries it binds to.
    implementation("fr.acinq.secp256k1:secp256k1-kmp:0.17.3")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.17.3")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

application {
    mainClass.set("com.vitorpamplona.searchrelay.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
