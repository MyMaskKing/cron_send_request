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
            Prefs.setFailed(context, widgetId, true, e.message)
            false
        }
    }.also { WidgetStateStore.publish(context, widgetId) }

    /** 读取最近一次缓存（无缓存返回 null）。 */
    fun cached(context: Context, widgetId: Int): WidgetResponse? {
        val raw = Prefs.getCache(context, widgetId) ?: return null
        return runCatching { json.decodeFromString(WidgetResponse.serializer(), raw) }.getOrNull()
    }
}

/**
 * 把拉取失败的原始异常信息（ApiClient 抛出的 "HTTP 401"、OkHttp 的
 * "Unable to resolve host…" / "failed to connect … after 10000ms" 等）映射为
 * 用户可读的失败原因；无法归类时原样带出（后端返回的中文错误信息可直接阅读）。
 */
fun friendlyErrorMsg(raw: String?): String {
    val s = raw.orEmpty()
    val httpCode = Regex("""HTTP\s*(\d{3})""").find(s)?.groupValues?.get(1)
    return when {
        httpCode == "401" || httpCode == "403" -> "登录失效，请打开 App 重新登录"
        httpCode == "404" -> "服务器地址有误（404），请检查服务器设置"
        httpCode != null && httpCode.startsWith("5") -> "服务器异常（HTTP $httpCode），稍后自动重试"
        s.contains("Unable to resolve host", ignoreCase = true) ||
            s.contains("failed to connect", ignoreCase = true) ||
            s.contains("Network is unreachable", ignoreCase = true) ||
            s.contains("timeout", ignoreCase = true) ||
            s.contains("SSL", ignoreCase = true) -> "网络连不上服务器，恢复后自动重试"
        s.isNotBlank() -> "连接失败：$s"
        else -> "连接失败，稍后自动重试"
    }
}
