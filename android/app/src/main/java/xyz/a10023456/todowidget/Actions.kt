package xyz.a10023456.todowidget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 小组件生命周期：注册刷新任务、初次添加/系统更新时拉取数据、删除时清理。
 *
 * 点击动作（刷新/完成/折叠）统一走 [WidgetActionActivity]（actionStartActivity），
 * 不再使用 BroadcastReceiver：Glance 对 LazyColumn item 内的 actionSendBroadcast 会强制
 * 套透明 Activity trampoline，在部分设备上被拦截导致勾选无响应。
 */
class TodoAppWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodoAppWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        RefreshWorker.enqueue(context)
        // 初次添加/系统更新时各拉一次：已登录 App 或已配 token 即可
        val ready = Prefs.isLoggedIn(context)
        appWidgetIds.forEach { id ->
            if (ready || Prefs.isConfigured(context, id)) {
                CoroutineScope(Dispatchers.IO).launch {
                    WidgetRepo.refresh(context, id)
                    TodoAppWidget().updateAll(context)
                }
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { Prefs.clear(context, it) }
        super.onDeleted(context, appWidgetIds)
    }
}
