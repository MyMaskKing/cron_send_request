package xyz.a10023456.todowidget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColor
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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

/** 小组件渲染入口。 */
class TodoAppWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = id.resolveAppWidgetId()
        provideContent {
            val data = remember(appWidgetId) { WidgetRepo.cached(context, appWidgetId) }
            val failed = Prefs.isFailed(context, appWidgetId)
            val configured = Prefs.isConfigured(context, appWidgetId)
            WidgetRoot(
                configured = configured,
                data = data,
                failed = failed,
                widgetId = appWidgetId
            )
        }
    }
}

/** Glance 1.1.0 未公开 toAppWidgetId()；GlanceId 实现类持有 Int appWidgetId，反射取第一个 Int 字段。 */
private fun GlanceId.resolveAppWidgetId(): Int = try {
    val f = this::class.java.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }
    f?.isAccessible = true
    f?.getInt(this) ?: -1
} catch (_: Exception) {
    -1
}

@Composable
private fun WidgetRoot(configured: Boolean, data: WidgetResponse?, failed: Boolean, widgetId: Int) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(6.dp)
            .background(imageProvider = ImageProvider(R.drawable.bg_card)),
        contentAlignment = Alignment.TopStart
    ) {
        if (!configured) {
            NotConfigured(widgetId)
        } else {
            WidgetBody(data, failed, widgetId)
        }
    }
}

@Composable
private fun NotConfigured(widgetId: Int) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp).clickable(
            actionStartActivity<ConfigActivity>(
                actionParametersOf(Keys.AppWidgetId to widgetId)
            )
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("📝", style = TextStyle(fontSize = 22.sp))
        Spacer(GlanceModifier.height(6.dp))
        Text(
            "点此配置小组件",
            style = TextStyle(color = W.brand, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        )
    }
}

@Composable
private fun WidgetBody(data: WidgetResponse?, failed: Boolean, widgetId: Int) {
    val ctx = androidx.glance.LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Header(data, widgetId)
        Spacer(GlanceModifier.height(6.dp))
        if (failed) {
            Text(
                "⚠️ 连接失败，显示上次数据",
                style = TextStyle(color = W.overdue, fontSize = 11.sp),
                maxLines = 1
            )
            Spacer(GlanceModifier.height(2.dp))
        }
        val groups = data?.groups.orEmpty()
        if (groups.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxWidth().height(56.dp).defaultWeight(),
                contentAlignment = Alignment.Center
            ) {
                Text("🎉 当前范围没有待办", style = TextStyle(color = W.sub, fontSize = 13.sp))
            }
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                groups.forEach { g ->
                    item(itemId = g.id) { GroupRow(g, widgetId) }
                    val collapsed = Prefs.isCollapsed(ctx, widgetId, g.id)
                    if (!collapsed) {
                        items(g.children, itemId = { it.id }) { child ->
                            ChildRow(child, widgetId)
                        }
                    }
                }
            }
        }
        Spacer(GlanceModifier.height(4.dp))
        Footer(widgetId)
    }
}

@Composable
private fun Header(data: WidgetResponse?, widgetId: Int) {
    val ctx = androidx.glance.LocalContext.current
    val token = Prefs.getToken(ctx, widgetId)
    val baseUrl = Prefs.getBaseUrl(ctx, widgetId)
    val openUrl = "$baseUrl/tr/$token"
    Row(
        modifier = GlanceModifier.fillMaxWidth().clickable(
            actionStartActivity<MainActivity>(actionParametersOf(Keys.Url to openUrl))
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            "📝 待办",
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = W.text)
        )
        if (data != null) {
            Spacer(GlanceModifier.width(6.dp))
            StatChip(data.stats.pending, W.text)
            Spacer(GlanceModifier.width(4.dp))
            StatChip(data.stats.overdue, W.overdue)
            Spacer(GlanceModifier.width(4.dp))
            StatChip(data.stats.memo, W.sub)
        }
    }
}

@Composable
private fun StatChip(value: Int, color: ColorProvider) {
    Box(
        modifier = GlanceModifier
            .background(imageProvider = ImageProvider(R.drawable.bg_chip))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(value.toString(), style = TextStyle(color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp))
    }
}

@Composable
private fun GroupRow(g: WidgetGroup, widgetId: Int) {
    val ctx = androidx.glance.LocalContext.current
    val collapsed = Prefs.isCollapsed(ctx, widgetId, g.id)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(
                actionRunCallback<CollapseAction>(
                    actionParametersOf(Keys.RootId to g.id, Keys.AppWidgetId to widgetId)
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (g.collapsible) (if (collapsed) "▶ " else "▼ ") else "• ",
            style = TextStyle(color = W.sub, fontSize = 11.sp)
        )
        Spacer(GlanceModifier.width(2.dp))
        Text(
            g.title,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, color = W.text),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )
        if (g.due_label.isNotBlank()) {
            Spacer(GlanceModifier.width(6.dp))
            Text(
                g.due_label,
                style = TextStyle(fontSize = 11.sp, color = if (g.overdue) W.overdue else W.sub),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ChildRow(child: WidgetItem, widgetId: Int) {
    val ctx = androidx.glance.LocalContext.current
    val token = Prefs.getToken(ctx, widgetId)
    val baseUrl = Prefs.getBaseUrl(ctx, widgetId)
    val openUrl = "$baseUrl/tr/$token"
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(start = 14.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 圆形勾选框（外层品牌圆 + 内层表面圆 = 环形），点击 → 完成
        Box(
            modifier = GlanceModifier
                .size(22.dp)
                .background(imageProvider = ImageProvider(R.drawable.bg_circle_brand))
                .clickable(
                    actionRunCallback<CompleteAction>(
                        actionParametersOf(
                            Keys.ItemId to child.id,
                            Keys.AppWidgetId to widgetId
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
        Spacer(GlanceModifier.width(8.dp))
        Text(
            child.title,
            style = TextStyle(fontSize = 13.sp, color = W.text),
            maxLines = 2,
            modifier = GlanceModifier.defaultWeight().clickable(
                actionStartActivity<MainActivity>(actionParametersOf(Keys.Url to openUrl))
            )
        )
    }
}

@Composable
private fun Footer(widgetId: Int) {
    val ctx = androidx.glance.LocalContext.current
    val updated = Prefs.getLastUpdated(ctx, widgetId)
    val time = if (updated > 0)
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(updated)) else "--:--"
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "＋ 新增",
            style = TextStyle(color = W.brand, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.clickable(
                actionStartActivity<AddTaskActivity>(
                    actionParametersOf(Keys.AppWidgetId to widgetId)
                )
            )
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            "↻ $time",
            style = TextStyle(color = W.sub, fontSize = 11.sp),
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
