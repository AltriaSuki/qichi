package app.qichi.shared.api

import kotlinx.serialization.Serializable

/**
 * App 的最新版本（GET /app/latest）。App 发现 [versionCode] 比自己新就提示更新，
 * 从 GET /app/apk 下载，核对 [sha256] 后交给系统安装（签名必须和装着的一样，系统才让覆盖）。
 */
@Serializable
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    /** 这一版改了什么（给人看的几句话） */
    val notes: String,
    val sizeBytes: Long,
    val sha256: String,
    val publishedAt: Timestamp,
)
