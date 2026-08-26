package xyz.a10023456.todowidget

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 小组件动作的统一承载 Activity（透明、无界面、finish 即退）。
 *
 * 不使用 BroadcastReceiver：Glance 对 [androidx.glance.appwidget.lazy.LazyColumn] item 内的
 * actionSendBroadcast 会强制套一层 InvisibleActionTrampolineActivity 再二次 sendBroadcast，
 * 该透明 Activity 在部分设备/ROM 上被拦截，导致勾选等点击完全无响应；且后台广播上下文弹 Toast
 * 也可能被系统压制。改走 actionStartActivity → 本 Activity：Activity→Activity 由用户点击发起，
 * 不受后台启动限制，Toast 在主线程前台可靠弹出。
 *
 * 动作类型与参数复用 [Keys]，与旧广播口径一致。
 */
class WidgetActionActivity : Activity() {

    companion object {
        const val ACTION_REFRESH = "refresh"
        const val ACTION_COMPLETE = "complete"
        const val ACTION_COLLAPSE = "collapse"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(Keys.AppWidgetId.name, -1)
        val actionType = intent.getStringExtra(Keys.ActionType.name)
        if (widgetId < 0 || actionType == null) {
            finish()
            return
        }

        when (actionType) {
            ACTION_COLLAPSE -> {
                val rootId = intent.getLongExtra(Keys.RootId.name, -1L)
                if (rootId > 0) Prefs.toggleCollapsed(this, widgetId, rootId)
                CoroutineScope(Dispatchers.IO).launch {
                    TodoAppWidget().updateAll(this@WidgetActionActivity)
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            ACTION_REFRESH -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val ok = WidgetRepo.refresh(this@WidgetActionActivity, widgetId)
                    TodoAppWidget().updateAll(this@WidgetActionActivity)
                    val msg = if (ok) "✅ 已刷新" else "❌ 刷新失败，请检查登录/网络"
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WidgetActionActivity, msg, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
            ACTION_COMPLETE -> {
                val itemId = intent.getLongExtra(Keys.ItemId.name, -1L)
                if (itemId <= 0) { finish(); return }
                CoroutineScope(Dispatchers.IO).launch {
                    val msg = runCatching { doComplete(widgetId, itemId) }
                        .getOrElse { "❌ 操作失败：${it.message ?: "未知错误"}" }
                    TodoAppWidget().updateAll(this@WidgetActionActivity)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WidgetActionActivity, msg, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
            else -> finish()
        }
    }

    /** 勾选完成：调接口后刷新缓存，返回提示文案。 */
    private suspend fun doComplete(widgetId: Int, itemId: Long): String {
        val baseUrl = Prefs.getBaseUrl(this, widgetId)
        val sid = Prefs.getSid(this)
        val token = Prefs.getToken(this, widgetId)
        val resp = ApiClient.markDone(baseUrl, sid, token, itemId)
        WidgetRepo.refresh(this, widgetId)
        return if (!resp.message.isNullOrBlank()) "✅ ${resp.message}" else "✅ 已完成"
    }
}
