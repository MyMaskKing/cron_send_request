package xyz.a10023456.todowidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 小组件点击统一接收器。
 *
 * 直接走 BroadcastReceiver（PendingIntent.getBroadcast），绕过 Glance
 * actionRunCallback 的 trampoline Activity（InvisibleActionTrampolineActivity →
 * ActionCallbackBroadcastReceiver）。部分设备/启动器上该 trampoline 会被拦截，
 * 导致 ActionCallback.onAction 完全不触发；直接广播可避免该问题。
 *
 * 通过 actionParametersOf 传入 [Keys.ActionType] 区分刷新/完成/折叠，其余参数复用
 * [Keys] 中已有键。
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(Keys.AppWidgetId.name, -1)
        if (widgetId < 0) return
        val actionType = intent.getStringExtra(Keys.ActionType.name) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var toastMsg: String? = null
            try {
                when (actionType) {
                    ACTION_REFRESH -> {
                        toastMsg = if (WidgetRepo.refresh(context, widgetId))
                            "✅ 已刷新" else "❌ 刷新失败，请检查登录/网络"
                    }
                    ACTION_COMPLETE -> {
                        val itemId = intent.getLongExtra(Keys.ItemId.name, -1L)
                        if (itemId > 0) toastMsg = doComplete(context, widgetId, itemId)
                    }
                    ACTION_COLLAPSE -> {
                        val rootId = intent.getLongExtra(Keys.RootId.name, -1L)
                        if (rootId > 0) Prefs.toggleCollapsed(context, widgetId, rootId)
                    }
                }
            } catch (e: Exception) {
                toastMsg = "❌ 操作失败：${e.message ?: "未知错误"}"
            } finally {
                TodoAppWidget().updateAll(context)
                withContext(Dispatchers.Main) {
                    toastMsg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    pending.finish()
                }
            }
        }
    }

    /** 勾选完成：调接口后刷新缓存，返回提示文案。 */
    private suspend fun doComplete(context: Context, widgetId: Int, itemId: Long): String {
        val baseUrl = Prefs.getBaseUrl(context, widgetId)
        val sid = Prefs.getSid(context)
        val token = Prefs.getToken(context, widgetId)
        val resp = ApiClient.markDone(baseUrl, sid, token, itemId)
        // 无论成功失败都刷新，把服务端真实状态拉回
        WidgetRepo.refresh(context, widgetId)
        return if (!resp.message.isNullOrBlank()) "✅ ${resp.message}" else "✅ 已完成"
    }

    companion object {
        const val ACTION_REFRESH = "refresh"
        const val ACTION_COMPLETE = "complete"
        const val ACTION_COLLAPSE = "collapse"
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
