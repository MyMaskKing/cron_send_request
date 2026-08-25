package xyz.a10023456.todowidget

import android.content.Context
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 手动刷新：重新拉取并更新当前小组件，并用 Toast 提示结果。 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: return
        Toast.makeText(context, "⏳ 刷新中…", Toast.LENGTH_SHORT).show()
        val ok = WidgetRepo.refresh(context, widgetId)
        TodoAppWidget().update(context, glanceId)
        Toast.makeText(
            context,
            if (ok) "✅ 已刷新" else "❌ 刷新失败，请检查登录/网络",
            Toast.LENGTH_SHORT
        ).show()
    }
}

/** 勾选完成：调用勾选接口，再刷新当前小组件，并用 Toast 提示结果（与网页端一致）。 */
class CompleteAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: return
        val itemId = parameters[Keys.ItemId] ?: return
        val result = withContext(Dispatchers.IO) {
            var ok = false
            var msg: String? = null
            try {
                val baseUrl = Prefs.getBaseUrl(context, widgetId)
                val sid = Prefs.getSid(context)
                val token = Prefs.getToken(context, widgetId)
                val resp = ApiClient.markDone(baseUrl, sid, token, itemId)
                ok = true
                msg = resp.message
            } catch (e: Exception) {
                msg = e.message
            }
            // 无论成功失败都刷新，把服务端真实状态拉回
            WidgetRepo.refresh(context, widgetId)
            ok to msg
        }
        TodoAppWidget().update(context, glanceId)
        val (ok, msg) = result
        val text = when {
            ok && !msg.isNullOrBlank() -> "✅ $msg"
            ok -> "✅ 已完成"
            else -> "❌ 操作失败：${msg ?: "未知错误"}"
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

/** 折叠/展开顶层分组：仅写本地状态，不发网络。 */
class CollapseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: return
        val rootId = parameters[Keys.RootId] ?: return
        Prefs.toggleCollapsed(context, widgetId, rootId)
        TodoAppWidget().update(context, glanceId)
    }
}

/** 小组件生命周期：注册刷新任务、删除时清理。 */
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
                GlobalScope.launch(Dispatchers.IO) {
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
