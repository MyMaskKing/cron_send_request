package xyz.a10023456.todowidget

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** 小组件数据仓储：拉取、缓存、读取。 */
object WidgetRepo {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** 拉取最新数据并写入缓存。成功返回 true，失败返回 false（调用方渲染缓存）。 */
    suspend fun refresh(context: Context, widgetId: Int): Boolean = withContext(Dispatchers.IO) {
        val sid = Prefs.getSid(context)
        val token = Prefs.getToken(context, widgetId)
        if (sid.isBlank() && token.isBlank()) return@withContext false
        val baseUrl = Prefs.getBaseUrl(context, widgetId)
        val scope = Prefs.getScope(context, widgetId)
        try {
            val resp = ApiClient.fetchWidget(baseUrl, sid, token, scope)
            if (!resp.success) return@withContext false
            Prefs.setCache(context, widgetId, json.encodeToString(WidgetResponse.serializer(), resp))
            true
        } catch (e: Exception) {
            Prefs.setFailed(context, widgetId, true)
            false
        }
    }

    /** 读取最近一次缓存（无缓存返回 null）。 */
    fun cached(context: Context, widgetId: Int): WidgetResponse? {
        val raw = Prefs.getCache(context, widgetId) ?: return null
        return runCatching { json.decodeFromString(WidgetResponse.serializer(), raw) }.getOrNull()
    }
}
