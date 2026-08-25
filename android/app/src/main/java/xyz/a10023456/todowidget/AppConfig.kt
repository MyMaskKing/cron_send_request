package xyz.a10023456.todowidget

import android.content.Context

/** App 级配置：服务器地址。小组件未单独配地址时回退到此。 */
object AppConfig {
    private const val PREFS = "app_config"
    private const val KEY_BASE_URL = "base_url"
    const val DEFAULT_BASE_URL = "https://cron.10023456.xyz"

    fun getBaseUrl(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        return normalize(raw)
    }

    fun setBaseUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BASE_URL, normalize(url)).apply()
    }

    /** 去尾部 /，并要求 https；非法输入回退默认地址 */
    fun normalize(url: String): String {
        val t = url.trim().trimEnd('/')
        return if (t.startsWith("http://") || t.startsWith("https://")) t else DEFAULT_BASE_URL
    }
}
