package xyz.a10023456.todowidget

import android.content.Context
import org.json.JSONObject

/** 按 appWidgetId 存储小组件配置、折叠状态与最近一次缓存。 */
object Prefs {
    private const val PREFS = "todo_widget_prefs"

    private fun sp(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 全局登录会话（App WebView 登录后由 MainActivity 同步）──
    fun setSid(context: Context, sid: String) =
        sp(context).edit().putString("sid", sid.trim()).apply()

    fun getSid(context: Context): String = sp(context).getString("sid", "") ?: ""

    fun clearSid(context: Context) = sp(context).edit().remove("sid").apply()

    /** 已在 App 内登录（存在会话 Cookie）。 */
    fun isLoggedIn(context: Context): Boolean = getSid(context).isNotBlank()

    fun getToken(context: Context, widgetId: Int): String =
        sp(context).getString("token_$widgetId", "") ?: ""

    fun setToken(context: Context, widgetId: Int, token: String) =
        sp(context).edit().putString("token_$widgetId", token.trim()).apply()

    /** scope ∈ cur|today|overdue|all，默认 cur */
    fun getScope(context: Context, widgetId: Int): String =
        sp(context).getString("scope_$widgetId", "cur") ?: "cur"

    fun setScope(context: Context, widgetId: Int, scope: String) =
        sp(context).edit().putString("scope_$widgetId", scope).apply()

    fun getBaseUrl(context: Context, widgetId: Int): String {
        val per = sp(context).getString("baseurl_$widgetId", "") ?: ""
        return if (per.isNotBlank()) AppConfig.normalize(per) else AppConfig.getBaseUrl(context)
    }

    fun isConfigured(context: Context, widgetId: Int): Boolean =
        getToken(context, widgetId).isNotBlank()

    fun isCollapsed(context: Context, widgetId: Int, rootId: Long): Boolean =
        sp(context).getBoolean("collapsed_${widgetId}_$rootId", false)

    fun setCollapsed(context: Context, widgetId: Int, rootId: Long, value: Boolean) =
        sp(context).edit().putBoolean("collapsed_${widgetId}_$rootId", value).apply()

    fun toggleCollapsed(context: Context, widgetId: Int, rootId: Long) {
        val cur = isCollapsed(context, widgetId, rootId)
        setCollapsed(context, widgetId, rootId, !cur)
    }

    fun setCache(context: Context, widgetId: Int, json: String) {
        sp(context).edit()
            .putString("cache_$widgetId", json)
            .putLong("updated_$widgetId", System.currentTimeMillis())
            .putBoolean("failed_$widgetId", false)
            .apply()
    }

    fun getCache(context: Context, widgetId: Int): String? =
        sp(context).getString("cache_$widgetId", null)

    fun getLastUpdated(context: Context, widgetId: Int): Long =
        sp(context).getLong("updated_$widgetId", 0L)

    fun setFailed(context: Context, widgetId: Int, failed: Boolean) =
        sp(context).edit().putBoolean("failed_$widgetId", failed).apply()

    fun isFailed(context: Context, widgetId: Int): Boolean =
        sp(context).getBoolean("failed_$widgetId", false)

    /** 删除某小组件时清理其全部键 */
    fun clear(context: Context, widgetId: Int) {
        val e = sp(context).edit()
        sp(context).all.keys.filter { it.endsWith("_$widgetId") || it.contains("_${widgetId}_") }
            .forEach { e.remove(it) }
        e.remove("cache_$widgetId").remove("updated_$widgetId")
            .remove("failed_$widgetId").remove("token_$widgetId")
            .remove("scope_$widgetId").remove("baseurl_$widgetId").apply()
    }

    /** 返回所有已配置（有 token）的 widgetId，用于周期刷新。 */
    fun allConfiguredWidgetIds(context: Context): List<Int> {
        val ids = mutableListOf<Int>()
        sp(context).all.keys.forEach { key ->
            if (key.startsWith("token_")) {
                val v = sp(context).getString(key, "") ?: ""
                if (v.isNotBlank()) {
                    key.removePrefix("token_").toIntOrNull()?.let { ids.add(it) }
                }
            }
        }
        return ids
    }

    // 保留 JSON 构造引用，避免极简后端响应下 proguard 误删（debug 不混淆，冗余安全）
    @Suppress("unused")
    private fun touchJson() { JSONObject() }
}
