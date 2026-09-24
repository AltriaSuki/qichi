package app.qichi.feature.writing

import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftRangesTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `周四：最近一周、上个周末、这个月、上个月`() {
        val r = DraftRanges.presets(d("2026-09-24")).associate { it.label to (it.start to it.end) }
        assertEquals(d("2026-09-18") to d("2026-09-24"), r["最近一周"])
        assertEquals(d("2026-09-19") to d("2026-09-20"), r["上个周末"])
        assertEquals(d("2026-09-01") to d("2026-09-24"), r["这个月"])
        assertEquals(d("2026-08-01") to d("2026-08-31"), r["上个月"])
    }

    @Test
    fun `周日算这个周末；每个范围都不超过 31 天`() {
        val sunday = DraftRanges.presets(d("2026-09-27"))
        assertEquals(d("2026-09-26") to d("2026-09-27"), sunday.single { it.label == "这个周末" }.let { it.start to it.end })
        listOf("2026-03-31", "2026-09-27", "2026-01-15").forEach { day ->
            DraftRanges.presets(d(day)).forEach { assertTrue(ChronoUnit.DAYS.between(it.start, it.end) < 31, it.toString()) }
        }
    }
}
