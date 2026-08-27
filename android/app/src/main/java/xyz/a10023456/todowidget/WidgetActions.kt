package xyz.a10023456.todowidget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/**
 * 小组件点击动作统一走 Glance [ActionCallback]（actionRunCallback）。
 *
 * Glance 内部通过 AppWidgetId 的 PendingIntent → 自带透明 trampoline Activity 瞬间转发到
 * [androidx.glance.appwidget.action.ActionCallbackBroadcastReceiver]（goAsync 后台协程）执行
 * onAction，trampoline 不做任何等待立即 finish，因此不会像自写透明 Activity（在 onCreate 里
 * 等网络返回才 finish）那样造成小组件「先隐藏 → 卡一下 → 再出现」的窗口闪烁；同时绕开了
 * LazyColumn item 内 actionSendBroadcast 在部分 ROM 被后台广播限制拦截的问题。
 *
 * 交互节奏（刷新/完成）：onAction 里只做瞬时操作——写 loading 状态并 update 重绘，让用户立即
 * 看到遮罩反馈；网络请求与 done/error/idle 终态全部交给 [WidgetActionWorker]（WorkManager
 * 持久化托管）。广播结束后进程随时可能被系统杀掉，若把耗时流程放进程级协程，任务会随进程
 * 丢失：loading 遮罩已上屏却等不到终态，表现为"遮罩久不消失 / 勾选无反馈"；WorkManager
 * 任务在进程被杀后会自动重跑，终态帧不丢。遮罩期间点击由 NoopAction 拦截。
 *
 * onAction 由 Glance 在后台协程调度，可直接挂起。
 * 回调类必须有 public 无参构造（Glance 反射实例化）。
 */

/**
 * 定向重绘。
 *
 * session 存活时 update 走 updateGlance()，仅更新 glanceState；靠 provideContent 中对 LocalState
 * 的订阅触发整体重组重读磁盘，界面才会刷新（见 WidgetUi.kt）。runCatching 兜底：重绘失败
 * （如 session 瞬时未就绪）不能中断后续入队。
 */
private suspend fun updateWidgets(context: Context, glanceId: GlanceId) {
    runCatching { TodoAppWidget().update(context, glanceId) }
}

/** 刷新：立即画 loading 遮罩，拉取与结果提示交由 [WidgetActionWorker]。 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: glanceId.resolveAppWidgetId(context)
        val appCtx = context.applicationContext
        Log.d("TodoWidget", "RefreshAction.onAction, widget=$widgetId")
        Prefs.setUiState(appCtx, widgetId, "loading", "刷新中…")
        updateWidgets(appCtx, glanceId)
        Log.d("TodoWidget", "RefreshAction: loading update() returned, enqueue worker")
        WidgetActionWorker.enqueue(appCtx, widgetId, WidgetActionWorker.ACTION_REFRESH)
        Log.d("TodoWidget", "RefreshAction: worker enqueued, onAction end")
    }
}

/** 勾选完成：立即画 loading 遮罩，调接口/刷新与结果提示交由 [WidgetActionWorker]。 */
class CompleteAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: glanceId.resolveAppWidgetId(context)
        val itemId = parameters[Keys.ItemId] ?: -1L
        if (itemId <= 0) return
        val appCtx = context.applicationContext
        Log.d("TodoWidget", "CompleteAction.onAction, widget=$widgetId, item=$itemId")
        Prefs.setUiState(appCtx, widgetId, "loading", "处理中…")
        updateWidgets(appCtx, glanceId)
        WidgetActionWorker.enqueue(appCtx, widgetId, WidgetActionWorker.ACTION_COMPLETE, itemId)
        Log.d("TodoWidget", "CompleteAction: worker enqueued, onAction end")
    }
}

/** 折叠/展开分组：仅切换本地折叠状态后重绘（瞬时，无遮罩）。 */
class CollapseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: glanceId.resolveAppWidgetId(context)
        val rootId = parameters[Keys.RootId] ?: -1L
        val appCtx = context.applicationContext
        if (rootId > 0) Prefs.toggleCollapsed(appCtx, widgetId, rootId)
        updateWidgets(appCtx, glanceId)
    }
}

/** 遮罩专用：占点击位，拦截处理期间的误触，不做任何事。 */
class NoopAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {}
}
