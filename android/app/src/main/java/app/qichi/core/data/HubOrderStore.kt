package app.qichi.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** 「一起」里每组功能的先后和各功能点开的次数（P16-09），只存在这台手机上，不同步。 */
data class HubOrder(
    /** 分组名 → 页面 slug 的先后；没排过的组没有 */
    val orders: Map<String, List<String>> = emptyMap(),
    /** 页面 slug → 从「一起」点开的次数 */
    val uses: Map<String, Int> = emptyMap(),
)

@Singleton
class HubOrderStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val order: StateFlow<HubOrder> = state.asStateFlow()

    fun setOrder(group: String, slugs: List<String>) {
        prefs.edit().putString(ORDER + group, slugs.joinToString(",")).apply()
        state.update { it.copy(orders = it.orders + (group to slugs)) }
    }

    fun resetOrder(group: String) {
        prefs.edit().remove(ORDER + group).apply()
        state.update { it.copy(orders = it.orders - group) }
    }

    fun recordUse(slug: String) {
        val count = (state.value.uses[slug] ?: 0) + 1
        prefs.edit().putInt(USES + slug, count).apply()
        state.update { it.copy(uses = it.uses + (slug to count)) }
    }

    private fun read(): HubOrder {
        val all = prefs.all
        val orders = all.filterKeys { it.startsWith(ORDER) }.mapNotNull { (k, v) ->
            (v as? String)?.split(",")?.filter { it.isNotEmpty() }?.let { k.removePrefix(ORDER) to it }
        }.toMap()
        val uses = all.filterKeys { it.startsWith(USES) }.mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix(USES) to it } }.toMap()
        return HubOrder(orders, uses)
    }

    private companion object {
        const val PREFS = "qichi-together"
        const val ORDER = "order."
        const val USES = "uses."
    }
}
