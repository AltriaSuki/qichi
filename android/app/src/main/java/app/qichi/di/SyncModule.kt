package app.qichi.di

import android.content.Context
import app.qichi.BuildConfig
import app.qichi.core.auth.LocalDataCleaner
import app.qichi.core.auth.SessionManager
import app.qichi.core.auth.TokenStore
import app.qichi.core.data.AttachmentPreparer
import app.qichi.core.data.ChatRepository
import app.qichi.core.data.CalendarTransferRepository
import app.qichi.core.data.DataStoreProfileStore
import app.qichi.core.data.DocumentRepository
import app.qichi.core.data.DraftStore
import app.qichi.core.data.EventRepository
import app.qichi.core.data.FileRepository
import app.qichi.core.data.IdeaRepository
import app.qichi.core.data.MoodRepository
import app.qichi.core.data.PlanRepository
import app.qichi.core.data.ProfileStore
import app.qichi.core.data.QnaRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TodoRepository
import app.qichi.core.data.TrashRepository
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.FileUrls
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxProcessor
import app.qichi.core.sync.RealtimeClient
import app.qichi.core.sync.SyncEngine
import app.qichi.core.sync.SyncScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): QichiDatabase = QichiDatabase.create(context)

    @Provides
    @Singleton
    fun localStore(db: QichiDatabase): LocalStore = LocalStore(db)

    @Provides
    @Singleton
    fun syncEngine(api: ApiClient, db: QichiDatabase, store: LocalStore): SyncEngine = SyncEngine(api, db, store)

    @Provides
    @Singleton
    fun outboxProcessor(api: ApiClient, db: QichiDatabase, store: LocalStore): OutboxProcessor =
        OutboxProcessor(api, db, store)

    @Provides
    @Singleton
    fun syncScheduler(@ApplicationContext context: Context): SyncScheduler = SyncScheduler(context)

    @Provides
    @Singleton
    fun realtimeClient(
        api: ApiClient,
        tokenStore: TokenStore,
        syncEngine: SyncEngine,
        @ApplicationScope scope: CoroutineScope,
    ): RealtimeClient = RealtimeClient(api, tokenStore, syncEngine, BuildConfig.BASE_URL, scope)

    @Provides
    @Singleton
    fun profileStore(@ApplicationContext context: Context): ProfileStore = DataStoreProfileStore(context)

    @Provides
    @Singleton
    fun roomRepository(
        api: ApiClient,
        db: QichiDatabase,
        store: LocalStore,
        syncEngine: SyncEngine,
        scheduler: SyncScheduler,
        profile: ProfileStore,
    ): RoomRepository = RoomRepository(api, db, store, syncEngine, scheduler, profile)

    @Provides
    @Singleton
    fun moodRepository(db: QichiDatabase, store: LocalStore, scheduler: SyncScheduler, session: SessionManager): MoodRepository =
        MoodRepository(db, store, scheduler, session)

    @Provides
    @Singleton
    fun todoRepository(db: QichiDatabase, store: LocalStore, scheduler: SyncScheduler, session: SessionManager): TodoRepository =
        TodoRepository(db, store, scheduler, session)

    @Provides
    @Singleton
    fun fileRepository(api: ApiClient): FileRepository = FileRepository(api)

    @Provides
    @Singleton
    fun draftStore(db: QichiDatabase): DraftStore = DraftStore(db)

    @Provides
    @Singleton
    fun trashRepository(db: QichiDatabase, store: LocalStore, api: ApiClient, scheduler: SyncScheduler, session: SessionManager): TrashRepository =
        TrashRepository(db, store, api, scheduler, session)

    @Provides
    @Singleton
    fun attachmentPreparer(@ApplicationContext context: Context): AttachmentPreparer = AttachmentPreparer(context)

    @Provides
    @Singleton
    fun fileUrls(): FileUrls = FileUrls(BuildConfig.BASE_URL)

    @Provides
    @Singleton
    fun chatRepository(db: QichiDatabase, store: LocalStore, api: ApiClient, scheduler: SyncScheduler, session: SessionManager): ChatRepository =
        ChatRepository(db, store, api, scheduler, session)

    @Provides
    @Singleton
    fun eventRepository(db: QichiDatabase, store: LocalStore, scheduler: SyncScheduler, session: SessionManager): EventRepository =
        EventRepository(db, store, scheduler, session)

    @Provides
    @Singleton
    fun qnaRepository(
        db: QichiDatabase, store: LocalStore, api: ApiClient, sync: SyncEngine,
        scheduler: SyncScheduler, session: SessionManager,
    ): QnaRepository = QnaRepository(db, store, api, sync, scheduler, session)

    @Provides
    @Singleton
    fun planRepository(db: QichiDatabase, store: LocalStore, scheduler: SyncScheduler, session: SessionManager): PlanRepository =
        PlanRepository(db, store, scheduler, session)

    @Provides
    @Singleton
    fun ideaRepository(db: QichiDatabase, store: LocalStore, scheduler: SyncScheduler, session: SessionManager): IdeaRepository =
        IdeaRepository(db, store, scheduler, session)

    @Provides
    @Singleton
    fun documentRepository(
        db: QichiDatabase, store: LocalStore, api: ApiClient, drafts: DraftStore, scheduler: SyncScheduler, session: SessionManager,
    ): DocumentRepository = DocumentRepository(db, store, api, drafts, scheduler, session)

    @Provides
    @Singleton
    fun calendarTransferRepository(
        @ApplicationContext context: Context, api: ApiClient, sync: SyncEngine,
    ): CalendarTransferRepository = CalendarTransferRepository(context, api, sync)

    /** 登出时清掉本机保存的「我」与当前房间。 */
    @Provides
    @IntoSet
    fun profileCleaner(profile: ProfileStore): LocalDataCleaner = LocalDataCleaner { profile.clear() }

    /** 登出时清空本机数据库（实体、同步位置、发件箱、草稿）。 */
    @Provides
    @IntoSet
    fun databaseCleaner(db: QichiDatabase, scheduler: SyncScheduler): LocalDataCleaner = LocalDataCleaner {
        scheduler.cancelAll()
        withContext(Dispatchers.IO) { db.clearAllTables() }
    }
}
