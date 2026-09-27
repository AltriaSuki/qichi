package app.qichi.feature

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * 页面之间不互相引用（02-architecture.md：`feature` 之间不互相引用，只依赖 `core`；P15-04）：
 * `feature/<名字>/` 下的代码里不能出现别的页面的包名（import 或直接写全名都算），
 * 包名也要和目录一致（不然换个包名就绕过去了）。两个页面都要用的东西挪到 `core` 里。
 */
class FeatureIsolationTest {

    @Test
    fun `页面之间不互相引用`() {
        val problems = FeatureIsolation.check(featureDir())
        if (problems.isNotEmpty()) {
            fail("这些页面用了别的页面的代码，挪到 core 里两边一起用：\n" + problems.joinToString("\n") { "  $it" })
        }
    }

    @Test
    fun `查法：import 和写全名都算，注释和自己的包不算，包名要和目录一致`() {
        val code = """
            package app.qichi.feature.archive

            import app.qichi.feature.archive.ArchiveRow
            import app.qichi.core.ui.topTags
            import app.qichi.feature.ideas.TagsScreen
            // 以前用的是 app.qichi.feature.plan 里的
            /** 见 app.qichi.feature.todo */
            val tags = app.qichi.feature.ideas.topTags(texts)
        """.trimIndent()
        assertEquals(listOf("ideas", "ideas"), FeatureIsolation.referencesIn("archive", code))
        assertEquals(listOf("archive/Wrong.kt 的包名是 app.qichi.feature.ideas"), FeatureIsolation.packageProblems("archive/Wrong.kt", "archive", "package app.qichi.feature.ideas\n"))
        assertEquals(emptyList(), FeatureIsolation.packageProblems("archive/Right.kt", "archive", "package app.qichi.feature.archive\n"))
    }

    private fun featureDir(): File {
        // 单元测试的工作目录是 android/app；别的地方跑时用 QICHI_ANDROID_APP 指过来
        val app = System.getenv("QICHI_ANDROID_APP")?.let(::File) ?: File(".")
        return File(app, "src/main/java/app/qichi/feature").also { check(it.isDirectory) { "找不到页面代码：${it.absolutePath}" } }
    }
}

internal object FeatureIsolation {
    private val reference = Regex("""\bapp\.qichi\.feature\.([a-z][a-z0-9_]*)""")
    private val pkg = Regex("""^package\s+(\S+)""", RegexOption.MULTILINE)

    /** 整个 feature 目录里的问题，一条一行 */
    fun check(dir: File): List<String> =
        dir.walk().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.flatMap { file ->
            val path = file.relativeTo(dir).invariantSeparatorsPath
            val feature = path.substringBefore('/')
            val text = file.readText()
            packageProblems(path, feature, text) + referencesIn(feature, text).distinct().map { "$path 用了 feature.$it 里的代码" }
        }.toList()

    /** [feature] 这个页面的代码里提到的别的页面（按出现的顺序；注释里的不算） */
    fun referencesIn(feature: String, code: String): List<String> =
        code.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("package ") || it.startsWith("//") || it.startsWith("/*") || it.startsWith("*") }
            .flatMap { line -> reference.findAll(line).map { it.groupValues[1] } }
            .filter { it != feature }
            .toList()

    /** 包名和目录对不上 */
    fun packageProblems(path: String, feature: String, code: String): List<String> {
        val name = pkg.find(code)?.groupValues?.get(1) ?: return listOf("$path 没有包名")
        return if (name == "app.qichi.feature.$feature") emptyList() else listOf("$path 的包名是 $name")
    }
}
