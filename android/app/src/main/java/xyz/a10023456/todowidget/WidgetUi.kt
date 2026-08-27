package xyz.a10023456.todowidget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColor
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 小组件配色：day/night 两套，跟随系统深色（仅用于文字；圆角背景走 drawable 以兼容 Glance 1.1）。 */
private object W {
    val text = DayNightColor(day = Color(0xFF14141E), night = Color(0xFFF2F1F7))
    val sub = DayNightColor(day = Color(0xFF8890B8), night = Color(0xFF9A93B5))
    val overdue = DayNightColor(day = Color(0xFFCF1322), night = Color(0xFFFF6B6B))
    val brand = DayNightColor(day = Color(0xFFA855F7), night = Color(0xFFA855F7))
}

/** 点击进入 App 的目标地址：登录态走 /todo，未登录且有 token 时走免密报告页 /tr/:token。 */
private fun openUrlOf(ctx: Context, baseUrl: String, token: String): String =
    if (Prefs.isLoggedIn(ctx) || token.isBlank()) "$baseUrl/todo" else "$baseUrl/tr/$token"

/** 点击「新增」：跳到 App 待办页并由网页识别 ?add=1 自动弹出新建表单。 */
private fun addUrlOf(ctx: Context, baseUrl: String, token: String): String =
    openUrlOf(ctx, baseUrl, token) + "?add=1"

/** 点击任务：已登录跳到 /todo?edit=<id> 由网页自动打开编辑弹窗；未登录回退到免密报告页。 */
private fun editUrlOf(ctx: Context, baseUrl: String, token: String, itemId: Long): String =
    if (Prefs.isLoggedIn(ctx)) "$baseUrl/todo?edit=$itemId" else openUrlOf(ctx, baseUrl, token)

/** 小组件渲染入口。 */
class TodoAppWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = id.resolveAppWidgetId(context)
        android.util.Log.d("TodoWidget", "provideGlance: widget=$appWidgetId（新 session 组合开始）")
        // 初始化自动刷新：新 session 组合（新组件、开机/进程重启后重建）时，若已配置且
        // 无缓存或缓存超过刷新周期（RefreshWorker 15 分钟）未更新，静默拉取一次（不弹遮罩）。
        maybeAutoRefresh(context, appWidgetId)
        provideContent {
            // 订阅进程级状态流：动作/Worker/Repo 调 WidgetStateStore.publish 写入 StateFlow，
            // 直接驱动存活 session 的内容重组（Compose 原生机制）。不再依赖 Glance update()
            // 对存活 session 发的会话事件--真机日志已证明该事件不触发重组（遮罩/结果提示
            // 全部不上屏）。嵌套的 SP 读取（token/baseUrl 等）随整体重组一并拿到最新值；
            // session 死亡后的重建由各处的 update() 兜底（新组合从 SP 读初值）。
            val frame by WidgetStateStore.observe(context, appWidgetId).collectAsState()
            android.util.Log.d(
                "TodoWidget",
                "compose: widget=$appWidgetId ui=(${frame.uiState},${frame.uiMsg}) " +
                    "data=${frame.data != null} ready=${frame.ready}"
            )
            WidgetRoot(
                ready = frame.ready,
                data = frame.data,
                failed = frame.failed,
                widgetId = appWidgetId,
                collapsed = frame.collapsed,
                overlayState = frame.uiState,
                overlayMsg = frame.uiMsg,
                opacity = frame.opacity,
                fontScale = frame.fontScale
            )
        }
    }
}

/** 初始化自动刷新的缓存过期阈值：对齐 RefreshWorker 周期（15 分钟）。 */
private const val AUTO_REFRESH_STALE_MS = 15L * 60 * 1000

/** 已配置但无缓存或缓存过期时，入队一次静默刷新 Worker（失败不弹遮罩）。 */
private fun maybeAutoRefresh(context: Context, appWidgetId: Int) {
    if (appWidgetId < 0) return
    val ready = Prefs.isLoggedIn(context) || Prefs.isConfigured(context, appWidgetId)
    if (!ready) return
    val updated = Prefs.getLastUpdated(context, appWidgetId)
    val stale = Prefs.getCache(context, appWidgetId) == null ||
        System.currentTimeMillis() - updated > AUTO_REFRESH_STALE_MS
    if (stale) {
        android.util.Log.d("TodoWidget", "auto refresh enqueued: widget=$appWidgetId, updated=$updated")
        WidgetActionWorker.enqueue(
            context, appWidgetId, WidgetActionWorker.ACTION_REFRESH, silent = true
        )
    }
}

/** 字号档位（Prefs.getFontScale）→ 缩放系数：0=小 1=中 2=大 */
private fun fontFactor(scale: Int): Float = when (scale) {
    0 -> 0.88f
    2 -> 1.15f
    else -> 1f
}

/** 按档位缩放后的字号（sp）。 */
private fun fs(scale: Int, baseSp: Int) = (baseSp * fontFactor(scale)).sp

/**
 * 分组卡片背景 tint：bg_card 是纯色圆角 shape，用垫底 Image + tint 着色
 * （背景图片的 colorFilter 在 Glance 翻译层被忽略；ImageView tint 全版本生效，
 * shape 自身 18dp 圆角也全版本保留）。tint 色日/夜与 res/values(-night)/colors.xml
 * 的 widget_bg 一致，alpha 即用户设置的背景不透明度。
 */
private fun cardColorFilter(opacity: Int): ColorFilter =
    ColorFilter.tint(
        DayNightColor(
            day = Color(0xFFFFFFFF).copy(alpha = opacity / 100f),
            night = Color(0xFF1E1B2A).copy(alpha = opacity / 100f)
        )
    )

/** 一张圆角分组卡片的容器：垫底背景图 + 内容列。 */
@Composable
private fun CardScaffold(opacity: Int, content: @Composable () -> Unit) {
    Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Image(
            provider = ImageProvider(R.drawable.bg_card),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            colorFilter = cardColorFilter(opacity)
        )
        Column(
            modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun WidgetRoot(
    ready: Boolean,
    data: WidgetResponse?,
    failed: Boolean,
    widgetId: Int,
    collapsed: Set<Long>,
    overlayState: String,
    overlayMsg: String,
    opacity: Int,
    fontScale: Int
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(6.dp),
        contentAlignment = Alignment.TopStart
    ) {
        // 背景下沉到每个分组卡片（CardScaffold），卡片之间透明处露出壁纸；
        // opacity/fontScale 随 WidgetStateStore 帧驱动重组，设置面板拖动即实时预览。
        if (!ready) {
            NotReady(fontScale)
        } else {
            WidgetBody(data, failed, widgetId, collapsed, opacity, fontScale)
        }
        if (overlayState != "idle") WidgetOverlay(overlayState, overlayMsg, fontScale)
    }
}

/** 点击处理中的遮罩：半透明覆盖层拦截误触，居中显示状态图标 + 文案。 */
@Composable
private fun WidgetOverlay(state: String, msg: String, fontScale: Int) {
    val (emoji, bg) = when (state) {
        "loading" -> "⏳" to Color(0x9914141E)
        "error" -> "❌" to Color(0xDD14141E)
        else -> "🎉" to Color(0xDD14141E)
    }
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(bg))
            .clickable(actionRunCallback<NoopAction>()),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, style = TextStyle(fontSize = fs(fontScale, 30)))
            Spacer(GlanceModifier.height(8.dp))
            Text(
                msg,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = fs(fontScale, 13),
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

@Composable
private fun NotReady(fontScale: Int) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp).clickable(
            actionStartActivity<MainActivity>()
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("📝", style = TextStyle(fontSize = 22.sp))
        Spacer(GlanceModifier.height(6.dp))
        Text(
            "打开 App 登录后显示待办",
            style = TextStyle(color = W.brand, fontWeight = FontWeight.Medium, fontSize = fs(fontScale, 13))
        )
    }
}

@Composable
private fun WidgetBody(
    data: WidgetResponse?,
    failed: Boolean,
    widgetId: Int,
    collapsed: Set<Long>,
    opacity: Int,
    fontScale: Int
) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Header(data, widgetId, fontScale)
        Spacer(GlanceModifier.height(6.dp))
        if (failed) {
            Text(
                "⚠️ 连接失败，显示上次数据",
                style = TextStyle(color = W.overdue, fontSize = fs(fontScale, 11)),
                maxLines = 1
            )
            Spacer(GlanceModifier.height(2.dp))
        }
        val groups = data?.groups.orEmpty()
        if (groups.isEmpty()) {
            CardScaffold(opacity) {
                Box(
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🎉 当前范围没有待办", style = TextStyle(color = W.sub, fontSize = fs(fontScale, 13)))
                }
            }
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                groups.forEach { g ->
                    item(itemId = g.id) {
                        GroupCard(g, widgetId, collapsed.contains(g.id), opacity, fontScale)
                    }
                }
            }
        }
        Spacer(GlanceModifier.height(4.dp))
        Footer(widgetId, fontScale)
    }
}

/**
 * 分组卡片：一个主任务（顶层清单）一张圆角卡片。
 * 单主任务（无子任务，后端回退项 id == 分组 id）：标题行自带勾选圈，整行即一条可勾选任务，
 * 不再渲染重复子行（修复"只有一个主任务时内容被省略/重复"的问题）；
 * 其余分组：标题行为折叠头（保留折叠），展开时列出叶子任务行。
 */
@Composable
private fun GroupCard(
    g: WidgetGroup,
    widgetId: Int,
    isCollapsed: Boolean,
    opacity: Int,
    fontScale: Int
) {
    CardScaffold(opacity) {
        GroupTitleRow(g, widgetId, isCollapsed, fontScale)
        val solo = !g.collapsible && g.children.firstOrNull()?.id == g.id
        if (!solo && !isCollapsed) {
            g.children.forEach { child -> ChildRow(child, widgetId, fontScale) }
        }
    }
}

/** 卡片标题行：单主任务带勾选圈；否则折叠箭头 + 标题，右侧重复图标/截止日期。 */
@Composable
private fun GroupTitleRow(g: WidgetGroup, widgetId: Int, isCollapsed: Boolean, fontScale: Int) {
    val solo = !g.collapsible && g.children.firstOrNull()?.id == g.id
    val rowModifier = if (solo) {
        GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)
    } else {
        GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(
            actionRunCallback<CollapseAction>(
                actionParametersOf(
                    Keys.AppWidgetId to widgetId,
                    Keys.RootId to g.id
                )
            )
        )
    }
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        if (solo) {
            CheckCircle(widgetId, g.id)
            Spacer(GlanceModifier.width(8.dp))
            Text(
                g.title,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = fs(fontScale, 13), color = W.text),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
            )
        } else {
            Text(
                if (g.collapsible) (if (isCollapsed) "▶ " else "▼ ") else "• ",
                style = TextStyle(color = W.sub, fontSize = fs(fontScale, 11))
            )
            Spacer(GlanceModifier.width(2.dp))
            Text(
                g.title,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = fs(fontScale, 13), color = W.text),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
            )
        }
        if (g.recurring) {
            Spacer(GlanceModifier.width(6.dp))
            Text("🔁", style = TextStyle(fontSize = fs(fontScale, 11)))
        }
        if (g.due_label.isNotBlank()) {
            Spacer(GlanceModifier.width(6.dp))
            Text(
                g.due_label,
                style = TextStyle(
                    fontSize = fs(fontScale, 11),
                    color = if (g.overdue) W.overdue else W.sub
                ),
                maxLines = 1
            )
        }
    }
}

/** 圆形勾选框（外层品牌圆 + 内层表面圆 = 环形），点击 → 完成。 */
@Composable
private fun CheckCircle(widgetId: Int, itemId: Long) {
    Box(
        modifier = GlanceModifier
            .size(22.dp)
            .background(imageProvider = ImageProvider(R.drawable.bg_circle_brand))
            .clickable(
                actionRunCallback<CompleteAction>(
                    actionParametersOf(
                        Keys.AppWidgetId to widgetId,
                        Keys.ItemId to itemId
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = GlanceModifier.size(18.dp)
                .background(imageProvider = ImageProvider(R.drawable.bg_circle_surface))
        ) {}
    }
}

@Composable
private fun Header(data: WidgetResponse?, widgetId: Int, fontScale: Int) {
    val ctx = androidx.glance.LocalContext.current
    val token = Prefs.getToken(ctx, widgetId)
    val baseUrl = Prefs.getBaseUrl(ctx, widgetId)
    val openUrl = openUrlOf(ctx, baseUrl, token)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = GlanceModifier.defaultWeight().clickable(
                actionStartActivity<MainActivity>(actionParametersOf(Keys.Url to openUrl))
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                "📝 待办",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = fs(fontScale, 14), color = W.text)
            )
            if (data != null) {
                Spacer(GlanceModifier.width(6.dp))
                StatChip(data.stats.pending, W.text, fontScale)
                Spacer(GlanceModifier.width(4.dp))
                StatChip(data.stats.overdue, W.overdue, fontScale)
                Spacer(GlanceModifier.width(4.dp))
                StatChip(data.stats.memo, W.sub, fontScale)
            }
        }
        Spacer(GlanceModifier.width(8.dp))
        Text(
            "⚙️",
            style = TextStyle(fontSize = fs(fontScale, 14), color = W.sub),
            modifier = GlanceModifier.clickable(
                actionStartActivity<ConfigActivity>(
                    actionParametersOf(Keys.AppWidgetId to widgetId)
                )
            )
        )
    }
}

@Composable
private fun StatChip(value: Int, color: ColorProvider, fontScale: Int) {
    Box(
        modifier = GlanceModifier
            .background(imageProvider = ImageProvider(R.drawable.bg_chip))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(value.toString(), style = TextStyle(color = color, fontWeight = FontWeight.Bold, fontSize = fs(fontScale, 12)))
    }
}

@Composable
private fun ChildRow(child: WidgetItem, widgetId: Int, fontScale: Int) {
    val ctx = androidx.glance.LocalContext.current
    val token = Prefs.getToken(ctx, widgetId)
    val baseUrl = Prefs.getBaseUrl(ctx, widgetId)
    val editUrl = editUrlOf(ctx, baseUrl, token, child.id)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(start = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckCircle(widgetId, child.id)
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight().clickable(
            actionStartActivity<MainActivity>(actionParametersOf(Keys.Url to editUrl))
        )) {
            if (child.path.isNotEmpty()) {
                Text(
                    child.path.joinToString(" → "),
                    style = TextStyle(color = W.sub, fontSize = fs(fontScale, 10)),
                    maxLines = 1
                )
            }
            Text(
                child.title,
                style = TextStyle(fontSize = fs(fontScale, 13), color = W.text),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun Footer(widgetId: Int, fontScale: Int) {
    val ctx = androidx.glance.LocalContext.current
    val token = Prefs.getToken(ctx, widgetId)
    val baseUrl = Prefs.getBaseUrl(ctx, widgetId)
    val addUrl = addUrlOf(ctx, baseUrl, token)
    val updated = Prefs.getLastUpdated(ctx, widgetId)
    val time = if (updated > 0)
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(updated)) else "--:--"
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "＋ 新增",
            style = TextStyle(color = W.brand, fontSize = fs(fontScale, 12), fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.clickable(
                actionStartActivity<MainActivity>(
                    actionParametersOf(Keys.Url to addUrl)
                )
            )
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            "↻ $time",
            style = TextStyle(color = W.sub, fontSize = fs(fontScale, 11)),
            modifier = GlanceModifier.clickable(
                actionRunCallback<RefreshAction>(
                    actionParametersOf(Keys.AppWidgetId to widgetId)
                )
            )
        )
    }
}

/** 共享的 ActionParameters 键。 */
object Keys {
    val AppWidgetId = ActionParameters.Key<Int>("appWidgetId")
    val ItemId = ActionParameters.Key<Long>("itemId")
    val RootId = ActionParameters.Key<Long>("rootId")
    val Url = ActionParameters.Key<String>("extra_url")
}
