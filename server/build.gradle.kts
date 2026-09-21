plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "app.qichi"
version = "0.1.0"

application {
    mainClass.set("app.qichi.server.ApplicationKt")
    // 生成缩略图用到 java.awt，服务器上没有显示器
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("app.qichi:qichi-shared")

    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.partial.content)
    // 缩略图：JDK 自带 JPEG/PNG/GIF，补上 WebP 和更耐用的 JPEG（CMYK 等）解码
    implementation(libs.imageio.jpeg)
    implementation(libs.imageio.webp)
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.testcontainers.postgresql)
}

// 把版本号写进 build-info.properties，/health 返回它
tasks.processResources {
    val appVersion = project.version.toString()
    inputs.property("version", appVersion)
    filesMatching("build-info.properties") {
        expand("version" to appVersion)
    }
}

tasks.test {
    useJUnitPlatform()
    // 测试用 Testcontainers 启动 PostgreSQL，需要能访问 Docker
    maxParallelForks = 1
}
