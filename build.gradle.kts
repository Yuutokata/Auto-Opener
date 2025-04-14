plugins {
    kotlin("jvm") version "2.0.0-RC1"
    kotlin("plugin.serialization") version "1.9.22"
    id("io.ktor.plugin") version "3.0.3"
    application
}

group = "de.yuuto"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-auth-jvm")
    implementation("io.ktor:ktor-server-auth-jwt-jvm")
    implementation("io.ktor:ktor-server-websockets-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-jackson-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-default-headers")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("io.micrometer:micrometer-registry-prometheus:1.11.3")
    implementation("io.ktor:ktor-server-metrics-micrometer")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")
    implementation("org.litote.kmongo:kmongo:5.2.0")
    implementation("org.litote.kmongo:kmongo-coroutine:5.2.0")
}


kotlin {
    jvmToolchain(21)
}


application {
    mainClass.set("de.yuuto.autoOpener.AutoOpenerKt")

}