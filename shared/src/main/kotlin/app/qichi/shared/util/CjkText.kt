package app.qichi.shared.util

import kotlin.math.ceil

/**
 * 中文字数与阅读时长（共同写作、留言等处显示）。
 *
 * 计数规则：
 * - 汉字、假名、韩文：每个字算 1。
 * - 其它文字的字母与数字：连在一起的一串算 1 个词，例如 `Kotlin`、`iPhone15`、`2026`、`3.14`、`1,000`、`don't`、`well-known`。
 * - 标点、空白、符号、表情：不算。
 *
 * 阅读时长按每分钟 [READING_CHARS_PER_MINUTE] 字计算，向上取整；有内容时至少 1 分钟，空文本为 0。
 */
object CjkText {
    const val READING_CHARS_PER_MINUTE: Int = 400

    fun charCount(text: String): Int {
        var count = 0
        var inWord = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val next = i + Character.charCount(cp)
            when {
                isCjk(cp) -> {
                    count++
                    inWord = false
                }
                Character.isLetterOrDigit(cp) -> {
                    if (!inWord) count++
                    inWord = true
                }
                // 组合附加符号（如分解形式的 é）属于前一个字母，不打断单词
                isCombiningMark(cp) -> Unit
                // 词中间的撇号、连字符、小数点、千分位逗号不打断单词
                inWord && cp in WORD_JOINERS && isWordCharAt(text, next) -> Unit
                else -> inWord = false
            }
            i = next
        }
        return count
    }

    fun readingMinutes(text: String): Int = readingMinutes(charCount(text))

    fun readingMinutes(charCount: Int): Int =
        if (charCount <= 0) 0 else ceil(charCount / READING_CHARS_PER_MINUTE.toDouble()).toInt()

    private fun isCjk(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.BOPOMOFO -> true
        else -> false
    }

    private fun isCombiningMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    private fun isWordCharAt(text: String, index: Int): Boolean {
        if (index >= text.length) return false
        val cp = text.codePointAt(index)
        return Character.isLetterOrDigit(cp) && !isCjk(cp)
    }

    private val WORD_JOINERS = setOf('\''.code, '’'.code, '-'.code, '.'.code, ','.code, '_'.code)
}
