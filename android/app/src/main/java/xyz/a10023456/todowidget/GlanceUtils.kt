package xyz.a10023456.todowidget

import androidx.glance.GlanceId

/**
 * Glance 1.1.0 未公开 toAppWidgetId()；其 GlanceId 实现类持有 Int appWidgetId，
 * 反射取第一个 Int 字段。失败返回 -1。
 */
internal fun GlanceId.resolveAppWidgetId(): Int = try {
    val f = this::class.java.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }
    f?.isAccessible = true
    f?.getInt(this) ?: -1
} catch (_: Exception) {
    -1
}
