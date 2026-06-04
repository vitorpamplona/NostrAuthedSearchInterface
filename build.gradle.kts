plugins {
    // Kotlin 2.3.x is required to read Quartz 1.11.0's metadata (compiled with 2.3.0).
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.vitorpamplona.searchrelay"
version = "0.1.0"

repositories {
    mavenCentral()
    google() // quartz pulls androidx.sqlite (KMP), published on Google's Maven repo
    maven { url = uri("https://jitpack.io") } // Quartz built from amethyst main
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

    // Nostr toolkit — Quartz built from amethyst main via JitPack, which ships the new
    // relay-server engine + Flow<Event> REQ-responder. Brings its secp256k1 backend with it.
    implementation("com.github.vitorpamplona.amethyst:quartz:56240c10d2")

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
