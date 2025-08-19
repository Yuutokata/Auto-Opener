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
    maven {
        url = uri("https://packages.confluent.io/maven")
        name = "confluence"
    }
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
    implementation("io.ktor:ktor-server-rate-limit")

    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("io.micrometer:micrometer-registry-prometheus:1.11.3")
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")
    implementation("io.ktor:ktor-server-metrics-micrometer")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")
    implementation("org.litote.kmongo:kmongo:5.2.0")
    implementation("org.litote.kmongo:kmongo-coroutine:5.2.0")
    implementation("io.ktor:ktor-server-cors:3.0.3")

    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.github.loki4j:loki-logback-appender:1.5.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    implementation("ch.qos.logback.contrib:logback-json-classic:0.1.5")
    implementation("ch.qos.logback.contrib:logback-jackson:0.1.5")
    implementation("io.github.flaxoos:ktor-server-rate-limiting:2.1.2")

}


kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs += listOf("-Xjsr305=strict")
    }
}

application {
    mainClass.set("de.yuuto.autoOpener.AutoOpenerKt")

}
