plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "app.qichi"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.java.diff.utils)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.snakeyaml.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // 契约测试：检查 shared 里的枚举与 api/openapi.yaml 一致
    systemProperty("qichi.openapi", rootDir.resolve("../api/openapi.yaml").absolutePath)
}
