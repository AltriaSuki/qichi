package app.qichi.di

import android.content.Context
import android.os.Build
import app.qichi.BuildConfig
import app.qichi.core.auth.EncryptedTokenStore
import app.qichi.core.auth.LocalDataCleaner
import app.qichi.core.auth.SessionManager
import app.qichi.core.auth.TokenStore
import app.qichi.core.network.ApiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** 跟随整个 App 进程的协程作用域（同步、发件箱等后台工作）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindings {
    /** 允许没有任何 LocalDataCleaner 时注入空集合。 */
    @Multibinds
    abstract fun localDataCleaners(): Set<LocalDataCleaner>
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun tokenStore(@ApplicationContext context: Context): TokenStore = EncryptedTokenStore(context)

    @Provides
    @Singleton
    fun apiClient(tokenStore: TokenStore): ApiClient = ApiClient(
        engine = OkHttp.create {
            config {
                retryOnConnectionFailure(true)
                pingInterval(30, TimeUnit.SECONDS)
            }
        },
        baseUrl = BuildConfig.BASE_URL,
        tokenStore = tokenStore,
        clientVersion = BuildConfig.VERSION_NAME,
        logFailure = { path, e -> android.util.Log.w("QichiApi", "请求失败 $path：${e::class.simpleName} ${e.message}") },
    )

    @Provides
    @Singleton
    fun sessionManager(
        api: ApiClient,
        tokenStore: TokenStore,
        cleaners: Set<@JvmSuppressWildcards LocalDataCleaner>,
        @ApplicationScope scope: CoroutineScope,
    ): SessionManager = SessionManager(api, tokenStore, cleaners, deviceName = "${Build.MANUFACTURER} ${Build.MODEL}", scope)
}
