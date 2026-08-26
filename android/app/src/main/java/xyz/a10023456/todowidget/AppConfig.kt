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

    /**
     * 规范化地址：去尾部 /、要求 http(s) 前缀、非法输入回退默认地址。
     * 公网 http 自动升级 https：Cloudflare 会 301 强制跳 https，而 OkHttp 跟随
     * 301/302 时会把 PUT 降级为 GET，导致 PUT /api/todo/:id/done 命中 HTML 404。
     * 局域网/本机（Docker 本地、wrangler dev 等）保留 http，避免无证书环境无法连接。
     */
    fun normalize(url: String): String {
        var t = url.trim().trimEnd('/')
        if (!t.startsWith("http://") && !t.startsWith("https://")) t = DEFAULT_BASE_URL
        if (t.startsWith("http://")) {
            val host = t.removePrefix("http://").substringBefore('/').substringBefore(':').lowercase()
            val isPrivate = host == "localhost" || host == "127.0.0.1" ||
                host.startsWith("192.168.") || host.startsWith("10.") ||
                (host.startsWith("172.") && host.split('.').getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true)
            if (!isPrivate) t = "https://" + t.removePrefix("http://")
        }
        return t
    }
}
