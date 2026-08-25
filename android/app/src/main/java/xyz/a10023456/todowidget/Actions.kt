package xyz.a10023456.todowidget

import android.content.Context
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

/** 手动刷新：重新拉取并更新当前小组件。 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: return
        WidgetRepo.refresh(context, widgetId)
        TodoAppWidget().update(context, glanceId)
    }
}

/** 勾选完成：调用免密勾选接口，再刷新当前小组件。 */
class CompleteAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: return
        val itemId = parameters[Keys.ItemId] ?: return
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = Prefs.getBaseUrl(context, widgetId)
                val token = Prefs.getToken(context, widgetId)
                ApiClient.markDone(baseUrl, token, itemId)
            } catch (_: Exception) {
                // 失败也继续刷新，由刷新把服务端真实状态拉回
            }
            WidgetRepo.refresh(context, widgetId)
        }
        TodoAppWidget().update(context, glanceId)
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
        // 初次添加/系统更新时各拉一次
        appWidgetIds.forEach { id ->
            if (Prefs.isConfigured(context, id)) {
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
