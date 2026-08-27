package xyz.a10023456.todowidget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 小组件点击动作（刷新/勾选完成）的实际执行 Worker。
 *
 * 动作回调（[androidx.glance.appwidget.action.ActionCallback]）跑在 goAsync 广播里，广播结束后
 * 进程不再有活跃组件，可能被系统随时杀掉；若网络请求与终态写入都放在广播/进程级协程里，
 * 进程一死任务即丢——loading 遮罩已由 Glance 的 SessionWorker（同样是 WorkManager 托管）画出，
 * 但 done/error/idle 终态帧永远不会到来，表现为遮罩长时间不消失、勾选无反馈。
 *
 * 故广播里只做瞬时操作（写 loading 状态 + update 重绘），耗时流程全部交给本 Worker：
 * WorkManager 持久化任务、进程被杀后自动重跑，保证终态一定写入并触发重绘。
 *
 * 同一 widget 用唯一任务名 + REPLACE：新点击自动取消上一个未完成动作（等价于旧 activeJobs 语义）。
 * 不加网络约束：断网时请求快速失败/超时，error 终态可见，而不是任务排队卡 loading。
 *
 * 注意：doWork 在进程被杀后可能从头重跑——refresh 为 GET 天然幂等；complete 的 markDone
 * 重跑极小概率让周期任务多滚动一次（窗口仅网络请求的 1~2s），后端对已完成任务本就有滚动
 * 语义，可接受。
 */
class WidgetActionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val widgetId = inputData.getInt(KEY_WIDGET_ID, -1)
        Log.d(TAG, "doWork start: action=${inputData.getString(KEY_ACTION)}, widget=$widgetId, attempt=$runAttemptCount")
        if (widgetId < 0) return Result.success()
        val glanceId = GlanceAppWidgetManager(applicationContext)
            .getGlanceIds(TodoAppWidget::class.java)
            .firstOrNull { it.resolveAppWidgetId() == widgetId }
            ?: run {
                Log.w(TAG, "doWork: glanceId not found for widget=$widgetId（小组件已被移除？）")
                return Result.success()
            }

        try {
            when (inputData.getString(KEY_ACTION)) {
                ACTION_COMPLETE -> {
                    val itemId = inputData.getLong(KEY_ITEM_ID, -1L)
                    if (itemId <= 0) return Result.success()
                    val msg = withContext(Dispatchers.IO) {
                        val baseUrl = Prefs.getBaseUrl(applicationContext, widgetId)
                        val sid = Prefs.getSid(applicationContext)
                        val token = Prefs.getToken(applicationContext, widgetId)
                        val resp = ApiClient.markDone(baseUrl, sid, token, itemId)
                        WidgetRepo.refresh(applicationContext, widgetId)
                        if (!resp.message.isNullOrBlank()) resp.message else "已完成"
                    }
                    Prefs.setUiState(
                        applicationContext, widgetId, "done",
                        if (msg.isNotBlank()) msg else "已完成"
                    )
                }
                else -> {
                    val ok = WidgetRepo.refresh(applicationContext, widgetId)
                    Log.d(TAG, "refresh result: ok=$ok, widget=$widgetId")
                    Prefs.setUiState(
                        applicationContext, widgetId,
                        if (ok) "done" else "error",
                        if (ok) "已刷新" else "刷新失败，请检查登录/网络"
                    )
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "doWork cancelled（被新动作 REPLACE），widget=$widgetId")
            throw e // 被新动作 REPLACE 取消：不要写终态覆盖新动作的 loading
        } catch (e: Exception) {
            Log.e(TAG, "doWork action failed, widget=$widgetId", e)
            Prefs.setUiState(applicationContext, widgetId, "error", e.message ?: "操作失败")
        }
        // 业务成败均已落到遮罩状态，统一 success：不触发 WorkManager 重试，避免遮罩复位后又弹结果
        Log.d(TAG, "done/error frame: uiState=${Prefs.getUiState(applicationContext, widgetId)}, widget=$widgetId")
        updateWidget(glanceId)
        Log.d(TAG, "done frame update() returned, widget=$widgetId")
        delay(RESULT_HOLD_MS)
        Prefs.setUiState(applicationContext, widgetId, "idle", "")
        Log.d(TAG, "idle written: uiState=${Prefs.getUiState(applicationContext, widgetId)}, widget=$widgetId")
        updateWidget(glanceId)
        Log.d(TAG, "idle frame update() returned, widget=$widgetId")
        return Result.success().also { Log.d(TAG, "doWork end success, widget=$widgetId") }
    }

    /** 定向重绘；runCatching 兜底：重绘失败不能中断后续状态复位。 */
    private suspend fun updateWidget(glanceId: GlanceId) {
        runCatching { TodoAppWidget().update(applicationContext, glanceId) }
            .onFailure { Log.e(TAG, "update() threw", it) }
    }

    companion object {
        private const val TAG = "TodoWidget"
        const val ACTION_REFRESH = "refresh"
        const val ACTION_COMPLETE = "complete"

        private const val WORK_PREFIX = "todo_widget_action_"
        private const val KEY_WIDGET_ID = "widget_id"
        private const val KEY_ACTION = "action"
        private const val KEY_ITEM_ID = "item_id"

        /** 结果遮罩停留时长，给用户阅读庆祝词/错误。 */
        private const val RESULT_HOLD_MS = 1400L

        /** 入队一个动作任务；同 widget 上一个未完成任务会被 REPLACE 取消。 */
        fun enqueue(context: Context, widgetId: Int, action: String, itemId: Long = -1L) {
            val request = OneTimeWorkRequestBuilder<WidgetActionWorker>()
                .setInputData(
                    workDataOf(
                        KEY_WIDGET_ID to widgetId,
                        KEY_ACTION to action,
                        KEY_ITEM_ID to itemId
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_PREFIX + widgetId,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
