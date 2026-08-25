package xyz.a10023456.todowidget

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * App 主体：原生壳 + 单 WebView + 底部 5 Tab。
 * 待办/基金/体重/资产 在 WebView 内打开现有网页；「我的」是原生菜单。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deepUrl = intent?.getStringExtra(Keys.Url.name)
        setContent {
            MaterialTheme(
                colorScheme = if (isNight()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppShell(initialUrl = deepUrl)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 小组件深链：再次点入时加载指定 URL（AppShell 通过全局事件接收）
        intent.getStringExtra(Keys.Url.name)?.let { DeepLinkBus.emit(it) }
    }

    private fun isNight(): Boolean =
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
}

/** 跨 onNewIntent 的简单深链总线。 */
object DeepLinkBus {
    var listener: ((String) -> Unit)? = null
    fun emit(url: String) { listener?.invoke(url) }
}

private data class Tab(val label: String, val emoji: String, val path: String?)

private val TABS = listOf(
    Tab("待办", "📝", "/todo"),
    Tab("基金", "📈", "/fund"),
    Tab("体重", "⚖️", "/weight"),
    Tab("资产", "💰", "/asset"),
    Tab("我的", "👤", null)
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AppShell(initialUrl: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var baseUrl by remember { mutableStateOf(AppConfig.getBaseUrl(context)) }
    var selected by rememberSaveable { mutableStateOf(0) }
    var showMe by rememberSaveable { mutableStateOf(false) }
    var targetUrl by rememberSaveable { mutableStateOf(initialUrl ?: (baseUrl + TABS[0].path)) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // WebViewClient 只在 factory 创建一次，用 rememberUpdatedState 让它始终读到最新 baseUrl
    val currentBaseUrl by rememberUpdatedState(baseUrl)

    // 小组件再次点入的深链
    androidx.compose.runtime.DisposableEffect(Unit) {
        DeepLinkBus.listener = { url -> targetUrl = url; showMe = false }
        onDispose { DeepLinkBus.listener = null }
    }

    fun openPath(path: String) {
        targetUrl = baseUrl + path
        showMe = false
    }

    // App 内禁用网页返回：网页内跳转不与底部 Tab 同步，goBack 会造成画面与 Tab 错位。
    // 「我的」原生面板打开时，返回键仅关闭面板回到网页。
    BackHandler(enabled = true) {
        if (showMe) showMe = false
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = if (tab.path == null) showMe else (!showMe && selected == i),
                        onClick = {
                            if (tab.path == null) {
                                showMe = true
                            } else {
                                selected = i
                                openPath(tab.path)
                            }
                        },
                        icon = { Text(tab.emoji) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // WebView 始终存在（保留登录态/历史），仅在「我的」页隐藏
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        settings.mediaPlaybackRequiresUserGesture = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        // 标记原生壳环境：服务端据此隐藏顶部网站导航，底部由原生 Tab 提供导航
                        CookieManager.getInstance().setCookie(currentBaseUrl, "app_shell=1; Path=/")
                        setBackgroundColor(Color.TRANSPARENT)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView, request: WebResourceRequest
                            ): Boolean {
                                val uri = request.url
                                val sameHost = uri.host == Uri.parse(currentBaseUrl).host
                                return if (sameHost) {
                                    false // 留在 WebView
                                } else {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    true
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                // 同步登录会话给小组件：从 Cookie 取 sid（native 可读 HttpOnly Cookie）
                                val cookieUrl = url ?: currentBaseUrl
                                val cookies = CookieManager.getInstance().getCookie(cookieUrl) ?: ""
                                val sid = Regex("(?:^|;)\\s*sid=([^;]+)").find(cookies)?.groupValues?.get(1)
                                val oldSid = Prefs.getSid(context)
                                when {
                                    !sid.isNullOrBlank() && sid != oldSid -> {
                                        // 登录/会话变更：写入 sid
                                        Prefs.setSid(context, sid)
                                        // 登录成功后若停在登录页/dashboard 等非 Tab 页，自动切到待办 Tab
                                        val path = url?.let { Uri.parse(it).path } ?: ""
                                        val onTab = TABS.any { t ->
                                            t.path != null && (path == t.path || path.startsWith(t.path + "/"))
                                        }
                                        if (!onTab) {
                                            selected = 0
                                            showMe = false
                                            targetUrl = currentBaseUrl + "/todo"
                                        }
                                        // 立即直接拉取所有桌面小组件数据并刷新（WorkManager 一次性任务可能被系统延迟，
                                        // 这里同步拉取保证登录后马上出数据；成功会清掉旧的 failed 标志）
                                        kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
                                            val ids = GlanceAppWidgetManager(context)
                                                .getGlanceIds(TodoAppWidget::class.java)
                                                .map { it.resolveAppWidgetId() }
                                                .filter { it >= 0 }
                                            ids.forEach { id -> WidgetRepo.refresh(context, id) }
                                            TodoAppWidget().updateAll(context)
                                        }
                                    }
                                    sid.isNullOrBlank() && oldSid.isNotBlank() -> {
                                        // 登出/会话失效：清除并刷新小组件
                                        Prefs.clearSid(context)
                                        kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
                                            TodoAppWidget().updateAll(context)
                                        }
                                    }
                                }
                            }
                        }
                        webChromeClient = WebChromeClient()
                        loadUrl(targetUrl)
                        webViewRef = this
                    }
                },
                update = { wv ->
                    if (wv.url != targetUrl) {
                        CookieManager.getInstance().setCookie(baseUrl, "app_shell=1; Path=/")
                        wv.loadUrl(targetUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (showMe) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MeScreen(
                    baseUrl = baseUrl,
                    currentUrl = { webViewRef?.url ?: baseUrl },
                    onOpenPath = { path -> openPath(path) },
                    onChangeBaseUrl = { newUrl ->
                        AppConfig.setBaseUrl(context, newUrl)
                        CookieManager.getInstance().removeAllCookies(null)
                        Prefs.clearSid(context)
                        baseUrl = AppConfig.normalize(newUrl)
                        targetUrl = baseUrl + "/todo"
                        selected = 0
                        showMe = false
                        webViewRef?.loadUrl(targetUrl)
                    },
                    onOpenInBrowser = {
                        val url = webViewRef?.url ?: baseUrl
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    onLogout = {
                        CookieManager.getInstance().removeAllCookies(null)
                        Prefs.clearSid(context)
                        targetUrl = baseUrl + "/login"
                        showMe = false
                        webViewRef?.loadUrl(targetUrl)
                    }
                )
                }
            }
        }
    }
}
