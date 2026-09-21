// AGP 9 自带 Kotlin 支持，不需要 org.jetbrains.kotlin.android；
// Compose 编译器插件把 Kotlin 版本固定为版本清单里的 kotlin。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
