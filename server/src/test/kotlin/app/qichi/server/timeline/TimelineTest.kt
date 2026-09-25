package app.qichi.server.timeline

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.MutableClock
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.CompletePlanRequest
import app.qichi.shared.api.CreateDecisionRequest
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.CreatePlanRequest
import app.qichi.shared.api.Decision
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Message
import app.qichi.shared.api.OnThisDay
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.api.TimelinePage
import app.qichi.shared.api.TimelinePick
import app.qichi.shared.api.UpdateDecisionRequest
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.TimelineEntryKind
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    private fun png(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private val clock = MutableClock(Instant.parse("2026-09-15T04:00:00Z"))

    @Test fun `本月的时间线：定下的决定、灵感、完成的计划按时间排；没定的决定不出现`() = serverTest(testContext(clock = clock)) { client ->
        val (aqi, chi, room) = Api(client).pair()
        val base = "/api/v1/rooms/$room"
        aqi.post("$base/ideas", CreateIdeaRequest(UuidV7.generate(), "阳台种一棵柠檬树"))
        clock.advance(Duration.ofMinutes(1))
        val d = chi.post("$base/decisions", CreateDecisionRequest(UuidV7.generate(), "周末去哪", listOf("海边"))).body<Decision>()
        chi.post("$base/decisions", CreateDecisionRequest(UuidV7.generate(), "还没定的事"))
        chi.patch("$base/decisions/${d.id}", UpdateDecisionRequest(finalChoice = Patch.of("海边")))
        clock.advance(Duration.ofMinutes(1))
        val planId = UuidV7.generate()
        aqi.post("$base/plans", CreatePlanRequest(planId, "把书架装好", aqi.userId()))
        aqi.post("$base/plans/$planId/complete", CompletePlanRequest("装好了，还剩两层空着。"))

        val page = aqi.get("$base/timeline").body<TimelinePage>()
        assertEquals(2026 to 9, page.year to page.month)
        assertEquals(listOf(TimelineEntryKind.Idea, TimelineEntryKind.Decision, TimelineEntryKind.Plan), page.entries.map { it.kind })
        assertEquals("海边", page.entries[1].detail)
        assertEquals("装好了，还剩两层空着。", page.entries[2].detail)
        assertEquals(listOf(3), page.months.map { it.count })

        // 别的月份是空的
        assertTrue(aqi.get("$base/timeline?year=2020&month=1").body<TimelinePage>().entries.isEmpty())
        aqi.get("$base/timeline?year=2020").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test fun `照片要两个人都选中才上时间线；取消任一人的选中就下来`() = serverTest { client ->
        val api = Api(client)
        val (aqi, chi, room) = api.pair()
        val base = "/api/v1/rooms/$room"
        val file = aqi.upload(room, png()).body<FileMeta>()
        assertEquals(HttpStatusCode.NoContent, aqi.put("$base/timeline/picks/${file.id}", emptyMap<String, String>()).status)
        assertEquals(HttpStatusCode.NoContent, aqi.put("$base/timeline/picks/${file.id}", emptyMap<String, String>()).status, "重复选中不报错")
        assertTrue(aqi.get("$base/timeline").body<TimelinePage>().entries.none { it.kind == TimelineEntryKind.Photo })

        chi.put("$base/timeline/picks/${file.id}", emptyMap<String, String>())
        val photos = chi.get("$base/timeline").body<TimelinePage>().entries.filter { it.kind == TimelineEntryKind.Photo }
        assertEquals(listOf(file.id), photos.map { it.refId })
        assertEquals(file.id, photos.single().file?.id)
        assertEquals(setOf(aqi.userId(), chi.userId()), aqi.get("$base/timeline/picks").body<List<TimelinePick>>().map { it.userId }.toSet())

        assertEquals(HttpStatusCode.NoContent, chi.delete("$base/timeline/picks/${file.id}").status)
        assertTrue(aqi.get("$base/timeline").body<TimelinePage>().entries.none { it.kind == TimelineEntryKind.Photo })

        // 别的房间的文件、不存在的文件：404；非成员 404
        aqi.put("$base/timeline/picks/${UuidV7.generate()}", emptyMap<String, String>()).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        api.outsider(aqi).get("$base/timeline").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test fun `一年前的今天：取那一天（房间时区）聊天里的照片，不含撤回的`() = serverTest(testContext(clock = clock)) { client ->
        val (aqi, chi, room) = Api(client).pair()
        val base = "/api/v1/rooms/$room"
        val file = aqi.upload(room, png()).body<FileMeta>()
        val kept = aqi.post("$base/messages", SendMessageRequest(UuidV7.generate(), "image", "海边", fileId = file.id)).body<Message>()
        val other = chi.upload(room, png()).body<FileMeta>()
        val gone = chi.post("$base/messages", SendMessageRequest(UuidV7.generate(), "image", "", fileId = other.id)).body<Message>()
        chi.post("$base/messages/${gone.id}/retract")
        val day = chi.get("$base/on-this-day?date=2026-09-15").body<OnThisDay>()
        assertEquals(listOf(kept.id), day.photos.map { it.messageId })
        assertEquals(file.id, day.photos.single().file.id)
        assertTrue(chi.get("$base/on-this-day?date=2026-09-14").body<OnThisDay>().photos.isEmpty())
        chi.get("$base/on-this-day?date=x").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test fun `日记式时间线：没揭晓的问答不出现、揭晓后带着两份回答出现；同一天的心情、决定、照片都在那一天；文稿一天只列最新一版；计划阶段完成也上`() =
        serverTest(testContext(clock = clock)) { client ->
            val (aqi, chi, room) = Api(client).pair()
            val base = "/api/v1/rooms/$room"
            val zone = java.time.ZoneId.of("Asia/Shanghai")
            aqi.post("$base/moods", app.qichi.shared.api.CreateMoodRequest(UuidV7.generate(), app.qichi.shared.model.MoodLabel.Calm, 7, "午后晒了会儿太阳"))
            val d = chi.post("$base/decisions", CreateDecisionRequest(UuidV7.generate(), "国庆怎么过", listOf("先回家再去海边"))).body<Decision>()
            chi.patch("$base/decisions/${d.id}", UpdateDecisionRequest(finalChoice = Patch.of("先回家再去海边")))
            val photo = aqi.upload(room, png()).body<FileMeta>()
            aqi.post("$base/messages", SendMessageRequest(UuidV7.generate(), "image", "绿萝又长了", fileId = photo.id))
            aqi.put("$base/timeline/picks/${photo.id}", emptyMap<String, String>())
            chi.put("$base/timeline/picks/${photo.id}", emptyMap<String, String>())

            // 问答：阿栖答了，还没揭晓
            val today = aqi.get("$base/qna/today").body<app.qichi.shared.api.QnaToday>()
            aqi.put("$base/qna/rounds/${today.round.id}/answer", app.qichi.shared.api.WriteAnswerRequest(UuidV7.generate(), "你先把我的书都收好了"))
            aqi.post("$base/qna/rounds/${today.round.id}/confirm")
            assertTrue(aqi.get("$base/timeline").body<TimelinePage>().entries.none { it.kind == TimelineEntryKind.Qna }, "没揭晓不出现")

            // 文稿同一天存了两版：只列 v2
            val doc = UuidV7.generate()
            aqi.post("$base/documents", app.qichi.shared.api.CreateDocumentRequest(doc, "给明年秋天的信"))
            aqi.post("$base/documents/$doc/versions", app.qichi.shared.api.SaveDocumentVersionRequest(UuidV7.generate(), 0, "第一版"))
            chi.post("$base/documents/$doc/versions", app.qichi.shared.api.SaveDocumentVersionRequest(UuidV7.generate(), 1, "第二版多了几个字"))

            // 计划阶段完成
            val planId = UuidV7.generate()
            aqi.post("$base/plans", CreatePlanRequest(planId, "秋天去一次海边", aqi.userId()))
            val stageId = UuidV7.generate()
            aqi.post("$base/plans/$planId/stages", app.qichi.shared.api.CreatePlanStageRequest(stageId, "选地方", 0))
            aqi.patch("$base/plans/$planId/stages/$stageId", app.qichi.shared.api.UpdatePlanStageRequest(doneAt = Patch.of(clock.instant())))

            // 小迟也答了：揭晓
            chi.put("$base/qna/rounds/${today.round.id}/answer", app.qichi.shared.api.WriteAnswerRequest(UuidV7.generate(), "那天的纸箱比想象中多"))
            chi.post("$base/qna/rounds/${today.round.id}/confirm")

            val entries = chi.get("$base/timeline").body<TimelinePage>().entries
            val qna = entries.single { it.kind == TimelineEntryKind.Qna }
            assertEquals(today.question.text, qna.title)
            assertEquals(setOf("你先把我的书都收好了", "那天的纸箱比想象中多"), qna.answers.map { it.body }.toSet())
            val mood = entries.single { it.kind == TimelineEntryKind.Mood }
            assertEquals(app.qichi.shared.model.MoodLabel.Calm to 7, mood.moodLabel to mood.intensity)
            assertEquals("绿萝又长了", entries.single { it.kind == TimelineEntryKind.Photo }.detail, "照片带着说明")
            assertEquals(listOf(2), entries.filter { it.kind == TimelineEntryKind.Writing }.map { it.version })
            assertEquals("完成了「选地方」", entries.single { it.kind == TimelineEntryKind.PlanProgress }.detail)
            // 心情、决定、照片在同一天（房间时区）
            val days = entries.filter { it.kind in setOf(TimelineEntryKind.Mood, TimelineEntryKind.Decision, TimelineEntryKind.Photo) }
                .map { it.at.atZone(zone).toLocalDate() }.toSet()
            assertEquals(1, days.size)
        }
}
