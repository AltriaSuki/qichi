package app.qichi.core.auth

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.QichiJson
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 令牌的保存位置。正式实现加密后存 DataStore；测试用内存实现。 */
interface TokenStore {
    suspend fun read(): AuthTokens?
    suspend fun write(tokens: AuthTokens)
    suspend fun clear()
}

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "qichi_auth")

/**
 * 令牌用 Tink AEAD（AES-256-GCM）加密后存进 DataStore，密钥集由 Android Keystore 的主密钥保护。
 * 不用明文 SharedPreferences。
 */
class EncryptedTokenStore(private val context: Context) : TokenStore {
    private val mutex = Mutex()
    private var cached: AuthTokens? = null
    private var loaded = false

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override suspend fun read(): AuthTokens? = mutex.withLock {
        if (!loaded) {
            cached = context.authDataStore.data.first()[KEY]?.let { decrypt(it) }
            loaded = true
        }
        cached
    }

    override suspend fun write(tokens: AuthTokens) = mutex.withLock {
        val json = QichiJson.encodeToString(AuthTokens.serializer(), tokens)
        val cipher = aead.encrypt(json.toByteArray(), ASSOCIATED_DATA)
        context.authDataStore.edit { it[KEY] = Base64.encodeToString(cipher, Base64.NO_WRAP) }
        cached = tokens
        loaded = true
    }

    override suspend fun clear() = mutex.withLock {
        context.authDataStore.edit { it.remove(KEY) }
        cached = null
        loaded = true
    }

    /** 解密失败（例如系统清掉了 Keystore 密钥）时当作未登录。 */
    private fun decrypt(value: String): AuthTokens? = runCatching {
        val plain = aead.decrypt(Base64.decode(value, Base64.NO_WRAP), ASSOCIATED_DATA)
        QichiJson.decodeFromString(AuthTokens.serializer(), String(plain))
    }.getOrNull()

    private companion object {
        val KEY = stringPreferencesKey("tokens")
        val ASSOCIATED_DATA = "qichi-tokens".toByteArray()
        const val KEYSET_NAME = "qichi_token_keyset"
        const val KEYSET_PREFS = "qichi_token_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://qichi_token_master_key"
    }
}

/** 内存里的令牌存储（测试用）。 */
class InMemoryTokenStore(private var tokens: AuthTokens? = null) : TokenStore {
    override suspend fun read(): AuthTokens? = tokens
    override suspend fun write(tokens: AuthTokens) {
        this.tokens = tokens
    }
    override suspend fun clear() {
        tokens = null
    }
}
