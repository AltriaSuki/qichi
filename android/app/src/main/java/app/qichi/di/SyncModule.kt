package app.qichi.di

import android.content.Context
import app.qichi.BuildConfig
import app.qichi.core.auth.LocalDataCleaner
import app.qichi.core.auth.TokenStore
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
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

    /** 登出时清空本机数据库（实体、同步位置、发件箱、草稿）。 */
    @Provides
    @IntoSet
    fun databaseCleaner(db: QichiDatabase, scheduler: SyncScheduler): LocalDataCleaner = LocalDataCleaner {
        scheduler.cancelAll()
        withContext(Dispatchers.IO) { db.clearAllTables() }
    }
}
