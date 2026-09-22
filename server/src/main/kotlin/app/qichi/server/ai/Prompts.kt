package app.qichi.server.ai

/**
 * 提示词模板：放在 resources/prompts/{name}.md，不写在代码里（docs/02-architecture.md）。
 * 模板里用 {{变量}} 占位；文件开头到第一行「---」之间是系统提示，之后是用户消息。
 */
class Prompts(private val load: (String) -> String? = { name -> Prompts::class.java.getResource("/prompts/$name.md")?.readText() }) {

    data class Rendered(val system: String, val user: String)

    fun render(name: String, vars: Map<String, String>): Rendered {
        val template = load(name) ?: error("缺少提示词模板 prompts/$name.md")
        var text = template
        for ((key, value) in vars) text = text.replace("{{$key}}", value)
        val leftover = Regex("\\{\\{(\\w+)}}").find(text)
        require(leftover == null) { "提示词模板 $name 里的 ${leftover?.value} 没有提供" }
        val parts = text.split(Regex("(?m)^---\\s*$"), limit = 2)
        return if (parts.size == 2) Rendered(parts[0].trim(), parts[1].trim()) else Rendered("", text.trim())
    }
}
