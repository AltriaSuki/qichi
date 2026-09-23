package app.qichi.server

import kotlinx.coroutines.launch
import app.qichi.server.auth.AuthService
import app.qichi.server.auth.PasswordHasher
import app.qichi.server.auth.TokenService
import app.qichi.server.calendar.CalendarService
import app.qichi.server.calendar.calendarRoutes
import app.qichi.server.auth.authRoutes
import app.qichi.server.ai.AiGateway
import app.qichi.server.ai.AiService
import app.qichi.server.ai.aiRoutes
import app.qichi.server.config.AppConfig
import app.qichi.server.config.ConfigException
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.RoomWriter
import app.qichi.server.events.EventService
import app.qichi.server.files.FileService
import app.qichi.server.files.FileStorage
import app.qichi.server.files.LocalFileStorage
import app.qichi.server.files.fileRoutes
import app.qichi.server.archive.ArchiveService
import app.qichi.server.archive.archiveRoutes
import app.qichi.server.board.BoardService
import app.qichi.server.decisions.DecisionService
import app.qichi.server.export.ExportService
import app.qichi.server.push.PushSender
import app.qichi.server.push.PushService
import app.qichi.server.push.UnifiedPushSender
import app.qichi.server.devices.DeviceService
import app.qichi.server.devices.deviceRoutes
import app.qichi.server.db.CompositeNotifier
import app.qichi.shared.model.PushProvider
import app.qichi.server.export.exportRoutes
import app.qichi.server.summaries.SummaryService
import app.qichi.server.review.DocumentConverter
import app.qichi.server.review.GotenbergConverter
import app.qichi.server.review.ReviewService
import app.qichi.server.review.reviewRoutes
import app.qichi.server.summaries.summaryRoutes
import app.qichi.server.reading.ReadingService
import app.qichi.server.reading.readingRoutes
import app.qichi.server.timeline.TimelineService
import app.qichi.server.timeline.timelineRoutes
import app.qichi.server.decisions.decisionRoutes
import app.qichi.server.board.boardRoutes
import app.qichi.server.documents.DocumentService
import app.qichi.server.documents.documentRoutes
import app.qichi.server.ideas.IdeaService
import app.qichi.server.ideas.ideaRoutes
import app.qichi.server.jobs.JobQueue
import app.qichi.server.life.lifeRoutes
import app.qichi.server.messages.MessageService
import app.qichi.server.messages.messageRoutes
import app.qichi.server.moods.MoodService
import app.qichi.server.todos.TodoService
import app.qichi.server.me.MeService
import app.qichi.server.plugins.installCallLogging
import app.qichi.server.plugins.installDefaultHeaders
import app.qichi.server.plugins.installErrorHandling
import app.qichi.server.plugins.installSecurity
import app.qichi.server.plugins.installSerialization
import app.qichi.server.plans.PlanService
import app.qichi.server.plans.planRoutes
import app.qichi.server.qna.QnaService
import app.qichi.server.qna.qnaRoutes
import app.qichi.server.rooms.RoomService
import app.qichi.server.rooms.roomRoutes
import app.qichi.server.sync.RealtimeHub
import app.qichi.server.sync.SyncService
import app.qichi.server.sync.installWebSockets
import app.qichi.server.sync.syncRoutes
import app.qichi.server.system.systemRoutes
import app.qichi.server.trash.TrashService
import app.qichi.server.trash.trashRoutes
import app.qichi.shared.api.API_PREFIX
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.Properties
import java.util.TimeZone
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("app.qichi.server.Application")

fun main() {
    // 数据库与接口一律用 UTC
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    val config = try {
        AppConfig.fromEnv()
    } catch (e: ConfigException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    val buildInfo = BuildInfo.load()
    log.info("栖迟服务端 {} 启动（{}），端口 {}", buildInfo.version, config.env, config.port)
    log.info("数据库：{}", config.database)

    val database = QichiDatabase.start(config.database)
    val ctx = AppContext(config, database, Clock.systemUTC(), buildInfo)

    when {
        ctx.ai.enabled -> log.info("AI：{}（{}）", config.ai.provider, config.ai.model)
        config.ai.isConfigured -> log.warn("AI_PROVIDER 只能是 openai-compatible 或 anthropic，现在是「{}」，AI 不可用", config.ai.provider)
        else -> log.info("AI：未配置")
    }

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(ctx)
        // 后台任务（AI 等）随服务一起启动和停止
        ctx.jobs.start(this)
        launch { runCatching { ctx.ai.ensureYearlyCheck() }.onFailure { log.warn("没能排上年度检查", it) } }
        monitor.subscribe(ApplicationStopped) { database.close() }
    }.start(wait = true)
}

/** 装插件、挂路由。测试里用 testApplication { application { module(ctx) } } 调用。 */
fun Application.module(ctx: AppContext) {
    installSerialization()
    installErrorHandling()
    installCallLogging()
    installDefaultHeaders()
    installSecurity(ctx.tokens, ctx.auth)
    installWebSockets()
    // 文件下载支持 Range（断点续传、视频拖动）
    install(PartialContent)

    routing {
        route(API_PREFIX) {
            systemRoutes(ctx)
            authRoutes(ctx)
            roomRoutes(ctx)
            syncRoutes(ctx)
            lifeRoutes(ctx)
            fileRoutes(ctx)
            messageRoutes(ctx)
            trashRoutes(ctx)
            aiRoutes(ctx)
            qnaRoutes(ctx)
            planRoutes(ctx)
            ideaRoutes(ctx)
            documentRoutes(ctx)
            boardRoutes(ctx)
            archiveRoutes(ctx)
            decisionRoutes(ctx)
            timelineRoutes(ctx)
            readingRoutes(ctx)
            summaryRoutes(ctx)
            exportRoutes(ctx)
            deviceRoutes(ctx)
            calendarRoutes(ctx)
            reviewRoutes(ctx)
        }
    }
}

/** 服务端运行所需的一切，由 main 创建后传给各个模块。 */
class AppContext(
    val config: AppConfig,
    val database: QichiDatabase,
    baseClock: Clock,
    val buildInfo: BuildInfo,
    hasher: PasswordHasher = PasswordHasher(),
    /** 默认按配置创建；测试里换成假的网关 */
    aiGateway: AiGateway? = AiGateway.fromConfig(config.ai),
    /** 默认按 PUSH_PROVIDERS 创建；测试里换成假的 */
    pushSender: PushSender? = if (PushProvider.UnifiedPush in config.pushProviders) UnifiedPushSender(config.unifiedPushAllowedHosts) else null,
    /** 默认按 CONVERTER_URL 创建；测试里换成假的 */
    converter: DocumentConverter? = config.converterUrl?.let(::GotenbergConverter),
) {
    /** 截到微秒：PostgreSQL 只存到微秒，这样写入与读回的时间完全相等。 */
    val clock: Clock = MicrosClock(baseClock)
    val realtime = RealtimeHub()
    val push = PushService(database, pushSender, clock)
    val writer = RoomWriter(CompositeNotifier(realtime, push))
    val devices = DeviceService(database, clock)
    val tokens = TokenService(config.jwtSecret, clock)
    val fileStorage: FileStorage = LocalFileStorage(config.filesDir)
    val files = FileService(database, fileStorage, clock)
    val rooms = RoomService(database, writer, clock, files)
    val auth = AuthService(database, hasher, tokens, rooms, clock)
    val me = MeService(database, writer, clock, aiEnabled = aiGateway != null)
    val sync = SyncService(database)
    val writes = EntityWrites(writer, clock)
    val moods = MoodService(database, rooms, writes)
    val todos = TodoService(database, rooms, writes)
    val events = EventService(database, rooms, writes)
    val messages = MessageService(database, rooms, writer, writes, files, clock)
    val jobs = JobQueue(database, clock)
    val ai = AiService(database, rooms, writer, jobs, aiGateway, config.ai, realtime, clock)
    val qna = QnaService(database, rooms, writes, writer, clock)
    val plans = PlanService(database, rooms, writes)
    val ideas = IdeaService(database, rooms, writes)
    val documents = DocumentService(database, rooms, writes)
    val board = BoardService(database, rooms, writes)
    val archive = ArchiveService(database, rooms, writes)
    val decisions = DecisionService(database, rooms, writes)
    val timeline = TimelineService(database, rooms, clock)
    val reading = ReadingService(database, rooms, writes)
    val summaries = SummaryService(database, rooms, writes)
    val export = ExportService(database, rooms, fileStorage, clock)
    val reviews = ReviewService(database, rooms, writes, writer, files, jobs, converter, clock)
    val trash = TrashService(database, rooms, writer, writes, todos, files, clock, reviews)
    val calendar = CalendarService(database, rooms, writes, writer, clock, config.publicBaseUrl)
}

class MicrosClock(private val base: Clock) : Clock() {
    override fun getZone(): java.time.ZoneId = base.zone
    override fun withZone(zone: java.time.ZoneId): Clock = MicrosClock(base.withZone(zone))
    override fun instant(): java.time.Instant = base.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
}

data class BuildInfo(val version: String) {
    companion object {
        fun load(): BuildInfo {
            val props = Properties()
            BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use(props::load)
            return BuildInfo(version = props.getProperty("version") ?: "dev")
        }
    }
}
