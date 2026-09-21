package app.qichi.server

import app.qichi.server.config.AppConfig
import app.qichi.server.config.ConfigException
import app.qichi.server.db.QichiDatabase
import app.qichi.server.plugins.installCallLogging
import app.qichi.server.plugins.installDefaultHeaders
import app.qichi.server.plugins.installErrorHandling
import app.qichi.server.plugins.installSerialization
import app.qichi.server.system.systemRoutes
import app.qichi.shared.api.API_PREFIX
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.Properties
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("app.qichi.server.Application")

fun main() {
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

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(ctx)
        monitor.subscribe(ApplicationStopped) { database.close() }
    }.start(wait = true)
}

/** 装插件、挂路由。测试里用 testApplication { application { module(ctx) } } 调用。 */
fun Application.module(ctx: AppContext) {
    installSerialization()
    installErrorHandling()
    installCallLogging()
    installDefaultHeaders()

    routing {
        route(API_PREFIX) {
            systemRoutes(ctx)
        }
    }
}

/** 服务端运行所需的一切，由 main 创建后传给各个模块。 */
class AppContext(
    val config: AppConfig,
    val database: QichiDatabase,
    val clock: Clock,
    val buildInfo: BuildInfo,
)

data class BuildInfo(val version: String) {
    companion object {
        fun load(): BuildInfo {
            val props = Properties()
            BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use(props::load)
            return BuildInfo(version = props.getProperty("version") ?: "dev")
        }
    }
}
