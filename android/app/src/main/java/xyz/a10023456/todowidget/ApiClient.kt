package xyz.a10023456.todowidget

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 服务端公开 API 客户端（仅 https，10s 超时）。所有方法为阻塞调用，需在 IO 线程执行。 */
object ApiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private fun baseUrlOf(baseUrl: String): String = AppConfig.normalize(baseUrl).trimEnd('/')

    /** GET 小组件分组数据；失败（网络/非 2xx/token 失效）抛异常。 */
    fun fetchWidget(baseUrl: String, token: String, scope: String): WidgetResponse {
        val url = "${baseUrlOf(baseUrl)}/api/public/todo-widget/$token?scope=$scope&limit=20"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { json.decodeFromString<ErrorResponse>(body) }.getOrNull()
                throw RuntimeException(err?.message ?: "HTTP ${resp.code}")
            }
            return json.decodeFromString<WidgetResponse>(body)
        }
    }

    /** 勾选完成（已完成的重复任务由后端自动滚动到下一次）。 */
    fun markDone(baseUrl: String, token: String, id: Long): DoneResponse {
        val url = "${baseUrlOf(baseUrl)}/api/public/todo-all/$token/$id/done"
        val req = Request.Builder().url(url)
            .put("""{"done":true}""".toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { json.decodeFromString<ErrorResponse>(body) }.getOrNull()
                throw RuntimeException(err?.message ?: "HTTP ${resp.code}")
            }
            return json.decodeFromString<DoneResponse>(body)
        }
    }

    /** 新增顶层任务（仅标题；其余字段在网页补充）。 */
    fun addTask(baseUrl: String, token: String, title: String): AddResponse {
        val url = "${baseUrlOf(baseUrl)}/api/public/todo-all/$token"
        val payload = JSONObject().put("title", title).toString()
        val req = Request.Builder().url(url)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { json.decodeFromString<ErrorResponse>(body) }.getOrNull()
                throw RuntimeException(err?.message ?: "HTTP ${resp.code}")
            }
            return json.decodeFromString<AddResponse>(body)
        }
    }
}
