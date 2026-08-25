package xyz.a10023456.todowidget

import kotlinx.serialization.Serializable

/** 小组件数据：后端 /api/public/todo-widget/:token 的响应 */
@Serializable
data class WidgetResponse(
    val success: Boolean = false,
    val owner_name: String? = null,
    val today: String? = null,
    val stats: WidgetStats = WidgetStats(),
    val groups: List<WidgetGroup> = emptyList()
)

@Serializable
data class WidgetStats(
    val total: Int = 0,
    val done: Int = 0,
    val pending: Int = 0,
    val overdue: Int = 0,
    val memo: Int = 0
)

@Serializable
data class WidgetGroup(
    val id: Long,
    val title: String,
    val due_label: String = "",
    val overdue: Boolean = false,
    val recurring: Boolean = false,
    val collapsible: Boolean = false,
    val children: List<WidgetItem> = emptyList()
)

@Serializable
data class WidgetItem(
    val id: Long,
    val title: String
)

/** PUT /api/public/todo-all/:token/:id/done 响应 */
@Serializable
data class DoneResponse(
    val success: Boolean = false,
    val message: String? = null,
    val cloned: Boolean = false,
    val next_id: Long? = null,
    val next_due: String? = null
)

/** POST /api/public/todo-all/:token 响应 */
@Serializable
data class AddResponse(
    val success: Boolean = false,
    val message: String? = null,
    val id: Long? = null
)

/** 通用错误体（后端失败时返回 {success:false, message}） */
@Serializable
data class ErrorResponse(
    val success: Boolean = false,
    val message: String? = null
)
