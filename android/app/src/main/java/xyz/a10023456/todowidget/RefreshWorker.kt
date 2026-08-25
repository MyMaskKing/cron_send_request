package xyz.a10023456.todowidget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** 周期拉取所有已添加小组件数据并更新。系统最小周期约 15 分钟。 */
class RefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 枚举桌面上当前存在的小组件（登录态为全局会话，不再依赖每个 widget 的 token）
        val ids = GlanceAppWidgetManager(applicationContext)
            .getGlanceIds(TodoAppWidget::class.java)
            .map { it.resolveAppWidgetId() }
            .filter { it >= 0 }
        ids.forEach { id -> WidgetRepo.refresh(applicationContext, id) }
        withContext(Dispatchers.Main) { TodoAppWidget().updateAll(applicationContext) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "todo_widget_refresh"
        private const val WORK_NAME_NOW = "todo_widget_refresh_now"

        /** 周期刷新（15 分钟，系统调度，App 关闭也能跑）。 */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** 立即刷新一次（登录/手动刷新后调用）。 */
        fun enqueueImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_NOW,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
