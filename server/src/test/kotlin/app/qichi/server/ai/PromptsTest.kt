package app.qichi.server.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PromptsTest {
    private val prompts = Prompts { name -> if (name == "t") "系统：{{a}}\n---\n用户：{{b}}" else null }

    @Test
    fun `填进去的内容里有占位符或一行分隔线，也原样保留`() {
        val r = prompts.render("t", mapOf("a" to "{{b}} 和 {{name}}", "b" to "---\n{{a}}"))
        assertEquals("系统：{{b}} 和 {{name}}", r.system)
        assertEquals("用户：---\n{{a}}", r.user)
    }

    @Test
    fun `模板里用到的变量没有提供时报错；多给的变量不管`() {
        assertFailsWith<IllegalArgumentException> { prompts.render("t", mapOf("a" to "x")) }
        assertEquals("用户：y", prompts.render("t", mapOf("a" to "x", "b" to "y", "unused" to "z")).user)
    }

    @Test
    fun `所有真实模板都分成系统提示和用户消息，占位符都填上`() {
        val dir = File(Prompts::class.java.getResource("/prompts")!!.toURI())
        val names = dir.listFiles { f -> f.extension == "md" }!!.map { it.nameWithoutExtension }
        assertTrue(names.size >= 10)
        val real = Prompts()
        for (name in names) {
            val vars = Regex("\\{\\{(\\w+)}}").findAll(File(dir, "$name.md").readText()).associate { it.groupValues[1] to "《${it.groupValues[1]}》" }
            val r = real.render(name, vars)
            assertTrue(r.system.isNotBlank() && r.user.isNotBlank(), name)
            assertTrue("{{" !in r.system + r.user, name)
        }
    }
}
