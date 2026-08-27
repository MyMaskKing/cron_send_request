package xyz.a10023456.todowidget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager

/**
 * 取小组件真实的 appWidgetId。Glance 1.1.0 公开了 GlanceAppWidgetManager.getAppWidgetId()
 * （内部类型转换后直读字段，官方明确用于兼容/IPC 场景）。
 *
 * 旧实现用反射取第一个 Int 字段，曾在真机上返回 0（= INVALID_APPWIDGET_ID，非法），
 * 导致 SP 键（cache_/ui_state_/token_…）与 Glance 会话/配置页使用的真实 id 错位：
 * 组件读不到配置页写入的数据、动作遮罩与状态读写分裂。失败返回 -1。
 */
internal fun GlanceId.resolveAppWidgetId(context: Context): Int = try {
    GlanceAppWidgetManager(context.applicationContext).getAppWidgetId(this)
} catch (_: Exception) {
    -1
}
