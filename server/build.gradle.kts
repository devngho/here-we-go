plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "io.github.devngho.herewego"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "io.github.devngho.herewego.server.ApplicationKt"
}

ktor {
    fatJar {
        archiveFileName = "herewego-server.jar"
    }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.json)
    // Kakao Local / Gemini 호출용 (M1 부터 사용)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.logback)
    implementation(libs.sqlite)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit)
}
