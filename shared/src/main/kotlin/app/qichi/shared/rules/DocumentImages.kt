package app.qichi.shared.rules

import java.util.UUID

/**
 * 文稿里的照片（P9-02）：正文里单独一行 `![说明](qichi-file:文件id)`，文件是上传到房间的图片。
 * 两端都按这里认：App 预览时显示图片，服务端导出时换成附件里的路径。
 */
object DocumentImages {
    const val SCHEME = "qichi-file:"

    /** 一整行都是图片标记 */
    val line = Regex("^!\\[([^\\]]*)]\\(" + Regex.escape(SCHEME) + "([0-9a-fA-F-]{36})\\)\\s*$")

    private val anywhere = Regex("!\\[([^\\]]*)]\\(" + Regex.escape(SCHEME) + "([0-9a-fA-F-]{36})\\)")

    fun markdown(fileId: UUID, alt: String = "照片"): String = "![${alt.replace("]", "")}]($SCHEME$fileId)"

    /** 正文里引用到的所有文件 id（按出现顺序，去重） */
    fun fileIds(body: String): List<UUID> =
        anywhere.findAll(body).mapNotNull { runCatching { UUID.fromString(it.groupValues[2]) }.getOrNull() }.distinct().toList()

    /** 把每个图片标记换成 [replace] 的结果（参数：说明、文件 id） */
    fun rewrite(body: String, replace: (alt: String, fileId: UUID) -> String): String =
        anywhere.replace(body) { m ->
            val id = runCatching { UUID.fromString(m.groupValues[2]) }.getOrNull() ?: return@replace m.value
            replace(m.groupValues[1], id)
        }
}
