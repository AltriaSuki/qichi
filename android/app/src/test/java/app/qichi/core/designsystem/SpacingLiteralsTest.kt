package app.qichi.core.designsystem

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * 页面里写死的间距（Q16）：规约是「间距只从设计系统（[Spacing]）取」。已有的记在 `spacing-literals.txt`（每个文件多少处），
 * 只许减少、不许增加：新写的页面用 [Spacing] 里的值；确实需要设计系统里没有的间距，先想想该不该加进 [Spacing]。
 *
 * 某个文件的数目减少了，把清单里那一行改小（或删掉）；也可以设环境变量 `QICHI_WRITE_SPACING_BASELINE=清单文件路径`
 * 跑一次这个测试，按现在的代码重写清单（只在确实减少了的时候这么做）。
 */
class SpacingLiteralsTest {

    @Test
    fun `页面里写死的间距只减不增`() {
        val now = SpacingLiterals.count(featureDir())
        System.getenv("QICHI_WRITE_SPACING_BASELINE")?.let { path ->
            File(path).writeText(SpacingLiterals.format(now))
            return
        }
        val baseline = SpacingLiterals.parse(javaClass.getResource("/spacing-literals.txt")!!.readText())
        val grown = now.filter { (file, n) -> n > (baseline[file] ?: 0) }
        if (grown.isNotEmpty()) {
            fail(
                "这些页面里写死的间距（padding / spacedBy 里的 N.dp）比清单多了，改用 Spacing 里的值：\n" +
                    grown.entries.joinToString("\n") { (file, n) -> "  $file：现在 $n 处，清单里 ${baseline[file] ?: 0} 处" },
            )
        }
    }

    @Test
    fun `数法：padding、spacedBy 括号里的 N·dp，设计系统里的值不算`() {
        val code = """
            Modifier.padding(horizontal = Spacing.page, vertical = 6.dp)
            Arrangement.spacedBy(Spacing.xs)
            Modifier.padding(
                start = 2.dp,
                end = 12.5.dp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {}
            Box(Modifier.size(22.dp, 12.dp))
            Text(style = type.caption.copy(fontSize = 12.tsp))
        """.trimIndent()
        assertEquals(4, SpacingLiterals.countIn(code), "size、字号不算；多行的 padding 也数")
    }

    private fun featureDir(): File {
        // 单元测试的工作目录是 android/app；别的地方跑时用 QICHI_ANDROID_APP 指过来
        val app = System.getenv("QICHI_ANDROID_APP")?.let(::File) ?: File(".")
        return File(app, "src/main/java/app/qichi/feature").also { check(it.isDirectory) { "找不到页面代码：${it.absolutePath}" } }
    }
}

/** 数页面代码里写死的间距。 */
internal object SpacingLiterals {
    /** padding(…)、spacedBy(…) 的括号里（括号里再套括号的少数几处不数） */
    private val call = Regex("""(?:padding|spacedBy)\(([^()]*)\)""")
    private val dp = Regex("""(?<![\w.])\d+(?:\.\d+)?\.dp\b""")

    fun countIn(code: String): Int = call.findAll(code).sumOf { m -> dp.findAll(m.groupValues[1]).count() }

    /** 每个文件（相对 feature 目录）有几处；没有的不列 */
    fun count(dir: File): Map<String, Int> =
        dir.walk().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to countIn(it.readText()) }
            .filterValues { it > 0 }
            .toSortedMap()

    fun format(counts: Map<String, Int>): String =
        "# 页面里写死的间距（见 SpacingLiteralsTest）：文件\t处数。只许减少。\n" +
            counts.entries.joinToString("") { (file, n) -> "$file\t$n\n" }

    fun parse(text: String): Map<String, Int> =
        text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.substringBefore('\t') to line.substringAfter('\t').trim().toInt() }
}
