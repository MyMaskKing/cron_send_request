package xyz.a10023456.todowidget

import android.content.Context
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 小组件点击动作统一走 Glance [ActionCallback]（actionRunCallback）。
 *
 * Glance 内部通过 AppWidgetId 的 PendingIntent → 自带透明 trampoline Activity 瞬间转发到
 * [androidx.glance.appwidget.action.ActionCallbackBroadcastReceiver]（goAsync 后台协程）执行
 * onAction，trampoline 不做任何等待立即 finish，因此不会像自写透明 Activity（在 onCreate 里
 * 等网络返回才 finish）那样造成小组件「先隐藏 → 卡一下 → 再出现」的窗口闪烁；同时绕开了
 * LazyColumn item 内 actionSendBroadcast 在部分 ROM 被后台广播限制拦截的问题。
 *
 * onAction 由 Glance 在后台协程调度，可直接挂起；Toast 切回主线程弹出。
 * 回调类必须有 public 无参构造（Glance 反射实例化）。
 */

/** 刷新：拉取最新数据 → 写缓存 → 重绘 → Toast 提示。 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: glanceId.resolveAppWidgetId()
        val ok = WidgetRepo.refresh(context, widgetId)
        TodoAppWidget().updateAll(context)
        val msg = if (ok) "✅ 已刷新" else "❌ 刷新失败，请检查登录/网络"
        withContext(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
}

/** 勾选完成：调接口 → 刷新缓存 → 重绘 → Toast 提示。 */
class CompleteAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: glanceId.resolveAppWidgetId()
        val itemId = parameters[Keys.ItemId] ?: -1L
        if (itemId <= 0) return
        val msg = withContext(Dispatchers.IO) {
            runCatching {
                val baseUrl = Prefs.getBaseUrl(context, widgetId)
                val sid = Prefs.getSid(context)
                val token = Prefs.getToken(context, widgetId)
                val resp = ApiClient.markDone(baseUrl, sid, token, itemId)
                WidgetRepo.refresh(context, widgetId)
                if (!resp.message.isNullOrBlank()) "✅ ${resp.message}" else "✅ 已完成"
            }.getOrElse { "❌ 操作失败：${it.message ?: "未知错误"}" }
        }
        TodoAppWidget().updateAll(context)
        withContext(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
}

/** 折叠/展开分组：仅切换本地折叠状态后重绘。 */
class CollapseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: glanceId.resolveAppWidgetId()
        val rootId = parameters[Keys.RootId] ?: -1L
        if (rootId > 0) Prefs.toggleCollapsed(context, widgetId, rootId)
        TodoAppWidget().updateAll(context)
    }
}
