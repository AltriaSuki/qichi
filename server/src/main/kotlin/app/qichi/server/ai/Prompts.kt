package app.qichi.server.ai

/**
 * 提示词模板：放在 resources/prompts/{name}.md，不写在代码里（docs/02-architecture.md）。
 * 模板里用 {{变量}} 占位；文件开头到第一行「---」之间是系统提示，之后是用户消息。
 */
class Prompts(private val load: (String) -> String? = { name -> Prompts::class.java.getResource("/prompts/$name.md")?.readText() }) {

    data class Rendered(val system: String, val user: String)

    /**
     * 先按模板里的「---」分成系统提示和用户消息，再各自一次性替换占位符（P13-03）：
     * 填进去的内容（聊天记录、问题……）即使本身含有 {{词}} 或一行 ---，也原样保留，不会被当成模板再处理一遍。
     * 模板里用到却没有提供的变量是写错了模板或调用方，直接报错。
     */
    fun render(name: String, vars: Map<String, String>): Rendered {
        val template = load(name) ?: error("缺少提示词模板 prompts/$name.md")
        val missing = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.filter { it !in vars }.toSet()
        require(missing.isEmpty()) { "提示词模板 $name 里的 ${missing.joinToString("、") { "{{$it}}" }} 没有提供" }
        fun fill(part: String) = PLACEHOLDER.replace(part) { m -> vars.getValue(m.groupValues[1]) }.trim()
        val parts = template.split(SEPARATOR, limit = 2)
        return if (parts.size == 2) Rendered(fill(parts[0]), fill(parts[1])) else Rendered("", fill(template))
    }

    private companion object {
        val PLACEHOLDER = Regex("\\{\\{(\\w+)}}")
        val SEPARATOR = Regex("(?m)^---\\s*$")
    }
}
