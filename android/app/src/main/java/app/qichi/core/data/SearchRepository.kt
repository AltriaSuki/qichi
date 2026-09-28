package app.qichi.core.data

import app.qichi.core.database.QichiDatabase
import app.qichi.core.search.SearchCorpus
import app.qichi.core.sync.LocalStore
import app.qichi.shared.api.Message
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 统一搜索（P16-06）要用的本机数据；聊天的旧消息不在本机时再问服务器。 */
@Singleton
class SearchRepository @Inject constructor(
    private val db: QichiDatabase,
    private val chat: ChatRepository,
) {
    /** 读出房间里能搜的各类数据（不含回收站里的）。解不开的行（更新的版本写的）跳过。 */
    suspend fun corpus(roomId: UUID): SearchCorpus {
        suspend fun <T : SyncEntity> load(type: EntityType): List<T> =
            db.entities().listByType(roomId.toString(), type.wireName).mapNotNull { row ->
                runCatching { LocalStore.toLocal<T>(row).value }.getOrNull()
            }
        return SearchCorpus(
            messages = load(EntityType.Message),
            todos = load(EntityType.Todo),
            events = load(EntityType.Event),
            plans = load(EntityType.Plan),
            milestones = load(EntityType.Milestone),
            planLogs = load(EntityType.PlanLog),
            ideas = load(EntityType.Idea),
            archive = load(EntityType.ArchiveItem),
            decisions = load(EntityType.Decision),
            documents = load(EntityType.Document),
            boardTopics = load(EntityType.BoardTopic),
            boardPosts = load(EntityType.BoardPost),
            books = load(EntityType.Book),
            highlights = load(EntityType.Highlight),
            questions = load(EntityType.Question),
            rounds = load(EntityType.QnaRound),
            answers = load(EntityType.Answer),
        )
    }

    /** 服务器上的聊天搜索（第一页，需要联网）：补上本机还没有的旧消息。 */
    suspend fun searchChatOnServer(roomId: UUID, query: String): List<Message> = chat.search(roomId, query, null).messages
}
