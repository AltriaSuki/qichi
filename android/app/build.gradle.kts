import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// 服务器地址从 android/local.properties 的 qichi.baseUrl 读入（不写死在代码里）
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
// 默认连本机开发服务端：模拟器里先执行 adb reverse tcp:8080 tcp:8080（见 SETUP-ARCH.md）
// 打正式包时可以临时指定：./gradlew :app:assembleRelease -Pqichi.baseUrl=https://qichi1.duckdns.org
val baseUrl: String = (findProperty("qichi.baseUrl") as String?) ?: localProperties.getProperty("qichi.baseUrl") ?: "http://127.0.0.1:8080"

android {
    namespace = "app.qichi"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.qichi"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BASE_URL", "\"${baseUrl.trimEnd('/')}\"")
    }

    // 正式版签名：local.properties 里有 qichi.release.* 时才配（钥匙和密码都不进仓库）
    val releaseStore = localProperties.getProperty("qichi.release.storeFile")
    if (releaseStore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore)
            storePassword = localProperties.getProperty("qichi.release.password")
            keyAlias = localProperties.getProperty("qichi.release.alias")
            keyPassword = localProperties.getProperty("qichi.release.password")
        }
    }

    buildTypes {
        release {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // 性能测试用：和正式版一样经过 R8、不可调试，但用调试签名，并允许用 HTTP 连本机开发服务端
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    sourceSets {
        getByName("benchmark") {
            res.srcDirs("src/debug/res")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // Readium 需要（minSdk 26 上补齐较新的 java.time 等 API）
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
}

// Room 数据库结构导出到 schemas/，纳入版本管理，用来写和校验迁移
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation("app.qichi:qichi-shared")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.websockets)
    implementation(libs.datastore.preferences)
    implementation(libs.tink.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.coil.compose)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.androidx.fragment.compose)
    // 连接器依赖桌面版 tink；App 已经有 tink-android（同一套类），去掉重复的
    implementation(libs.unifiedpush.connector) { exclude(group = "com.google.crypto.tink", module = "tink") }
    implementation(libs.coil.network.ktor)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
}
