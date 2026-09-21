package app.qichi.shared.api

import kotlinx.serialization.json.Json

/**
 * 两端共用的 JSON 配置。
 * - 未知字段忽略：服务端加了新字段，旧版 App 也能正常解析。
 * - 默认值与 null 都输出：openapi.yaml 里「必有但可为空」的字段总是出现在 JSON 里。
 */
val QichiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}
