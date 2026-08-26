package xyz.a10023456.todowidget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * 交互节奏（刷新/完成）：先写 loading 状态重绘 → 显示半透明遮罩，让用户立即感知在处理；
 * 请求回来写 done/error 显示结果；停留 1.4s 后清除状态重绘回正常内容。遮罩期间拦截重复点击。
 *
 * onAction 由 Glance 在后台协程调度，可直接挂起。
 * 回调类必须有 public 无参构造（Glance 反射实例化）。
 */

/** 遮罩停留的最短展示时长，保证 loading 帧能上屏（避免被系统合并）。 */
private const val LOADING_MIN_MS = 180L
/** 结果遮罩停留时长，给用户阅读庆祝词/错误。 */
private const val RESULT_HOLD_MS = 1400L

/**
 * 进程级、与广播生命周期解耦的协程作用域。
 *
 * ActionCallback.onAction 跑在 goAsync 广播里，pendingResult.finish() 只有在 onAction 返回后才
 * 调用；而系统对同一 BroadcastReceiver 的广播串行投递——若 onAction 内做网络(最长 10s)+延时，
 * 会占住广播，后续点击全部排队"无反应"。故 onAction 必须立即返回，把耗时流程丢到本作用域异步执行。
 * Prefs.uiState 带 6s 过期兜底，进程被杀时遮罩也不会常驻。
 */
private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** 每个 widget 当前在跑的动作 Job；同一 widget 新点击会取消上一次，避免堆叠。 */
private val activeJobs = mutableMapOf<Int, Job>()

/** 启动一个与 widget 绑定的异步动作；同 widget 上一个未完成的动作会被取消。 */
private fun runWidgetAction(widgetId: Int, block: suspend CoroutineScope.() -> Unit) {
    lateinit var job: Job
    job = actionScope.launch {
        try {
            block()
        } finally {
            // 仅在自己仍是当前活动 Job 时移除，避免清掉后续新启动的动作
            synchronized(activeJobs) {
                if (activeJobs[widgetId] === job) activeJobs.remove(widgetId)
            }
        }
    }
    synchronized(activeJobs) {
        activeJobs.remove(widgetId)?.cancel()
        activeJobs[widgetId] = job
    }
}

/**
 * 刷新小组件界面。
 *
 * Glance 的组合/翻译/RemoteViews 落地需要主线程推进；在后台线程直接调 updateAll 会导致重绘不推进
 * （与已验证可用的 RefreshWorker 一致，统一切到 [Dispatchers.Main]）。
 */
private suspend fun updateWidgets(context: Context) = withContext(Dispatchers.Main) {
    TodoAppWidget().updateAll(context)
}

/** 刷新：遮罩 → 拉取最新数据写缓存 → 结果提示 → 自动重绘。onAction 立即返回，流程在 actionScope 跑。 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: glanceId.resolveAppWidgetId()
        val appCtx = context.applicationContext
        runWidgetAction(widgetId) {
            Prefs.setUiState(appCtx, widgetId, "loading", "刷新中…")
            updateWidgets(appCtx)
            delay(LOADING_MIN_MS)
            val ok = WidgetRepo.refresh(appCtx, widgetId)
            Prefs.setUiState(
                appCtx, widgetId,
                if (ok) "done" else "error",
                if (ok) "已刷新" else "刷新失败，请检查登录/网络"
            )
            updateWidgets(appCtx)
            delay(RESULT_HOLD_MS)
            Prefs.setUiState(appCtx, widgetId, "idle", "")
            updateWidgets(appCtx)
        }
    }
}

/** 勾选完成：遮罩 → 调接口并刷新缓存 → 庆祝词/错误 → 自动重绘。 */
class CompleteAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: glanceId.resolveAppWidgetId()
        val itemId = parameters[Keys.ItemId] ?: -1L
        if (itemId <= 0) return
        val appCtx = context.applicationContext
        runWidgetAction(widgetId) {
            Prefs.setUiState(appCtx, widgetId, "loading", "处理中…")
            updateWidgets(appCtx)
            delay(LOADING_MIN_MS)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val baseUrl = Prefs.getBaseUrl(appCtx, widgetId)
                    val sid = Prefs.getSid(appCtx)
                    val token = Prefs.getToken(appCtx, widgetId)
                    val resp = ApiClient.markDone(baseUrl, sid, token, itemId)
                    WidgetRepo.refresh(appCtx, widgetId)
                    if (!resp.message.isNullOrBlank()) resp.message else "已完成"
                }
            }
            val (state, msg) = result.fold(
                onSuccess = { "done" to (if (it.isNotBlank()) it else "已完成") },
                onFailure = { "error" to (it.message ?: "操作失败") }
            )
            Prefs.setUiState(appCtx, widgetId, state, msg)
            updateWidgets(appCtx)
            delay(RESULT_HOLD_MS)
            Prefs.setUiState(appCtx, widgetId, "idle", "")
            updateWidgets(appCtx)
        }
    }
}

/** 折叠/展开分组：仅切换本地折叠状态后重绘（瞬时，无遮罩）。onAction 立即返回。 */
class CollapseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetId = parameters[Keys.AppWidgetId] ?: glanceId.resolveAppWidgetId()
        val rootId = parameters[Keys.RootId] ?: -1L
        val appCtx = context.applicationContext
        // 独立启动，不经过 activeJobs：折叠不应取消正在进行的刷新/完成动作
        if (rootId > 0) Prefs.toggleCollapsed(appCtx, widgetId, rootId)
        actionScope.launch { updateWidgets(appCtx) }
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
