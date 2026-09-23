pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 本机代理下 Gradle 偶尔下不动 Readium 的大文件：先看本机仓库（只限 Readium，本机没有就照常去 mavenCentral）
        mavenLocal {
            content { includeGroup("org.readium.kotlin-toolkit") }
        }
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "qichi-android"
include(":app")

// 以源码方式引入共享模块（app.qichi:qichi-shared）
includeBuild("../shared")
