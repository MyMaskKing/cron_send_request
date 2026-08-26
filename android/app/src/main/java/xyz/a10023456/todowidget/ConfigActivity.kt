package xyz.a10023456.todowidget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // 系统默认：用户取消配置则不放置小组件
        setResult(RESULT_CANCELED)

        val token = Prefs.getToken(this, appWidgetId)
        val scope = Prefs.getScope(this, appWidgetId)
        val baseUrl = AppConfig.getBaseUrl(this)

        setContent {
            MaterialTheme(colorScheme = if (isNight()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConfigScreen(
                        initialToken = token,
                        initialScope = scope,
                        baseUrl = baseUrl,
                        sid = Prefs.getSid(this),
                        onTest = { t -> testConnection(t) },
                        onSave = { t, s -> save(t, s) }
                    )
                }
            }
        }
    }

    private fun isNight(): Boolean =
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun testConnection(token: String) {
        val sid = Prefs.getSid(this)
        if (sid.isBlank() && token.isBlank()) {
            Toast.makeText(this, "请先在 App 内登录，或填写 report_token", Toast.LENGTH_SHORT).show()
            return
        }
        val scope = Prefs.getScope(this, appWidgetId)
        val baseUrl = AppConfig.getBaseUrl(this)
        val endpoint = if (sid.isNotBlank())
            "$baseUrl/api/todo-widget?scope=$scope&limit=20"
        else
            "$baseUrl/api/public/todo-widget/$token?scope=$scope&limit=20"
        Toast.makeText(this, "测试中…\n$endpoint", Toast.LENGTH_LONG).show()
        kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
            val result = runCatching {
                ApiClient.fetchWidget(baseUrl, sid, token, scope)
            }
            withContext(Dispatchers.Main) {
                val msg = result.fold(
                    onSuccess = {
                        if (it.success) {
                            // 测试数字必须与组件当前 scope 实际显示一致：groups 已按 scope 过滤，
                            // stats 是全量统计（含未来任务），不能用它判断"组件是否有数据"
                            val totalItems = it.groups.sumOf { g -> g.children.size }
                            val scopeLabel = when (scope) {
                                "today" -> "仅今天到期"
                                "overdue" -> "仅逾期"
                                "all" -> "全部未完成"
                                else -> "今日+逾期"
                            }
                            if (it.groups.isEmpty())
                                "✅ 连接成功，但「$scopeLabel」范围无待办\n" +
                                    "（总待办 ${it.stats.pending} / 逾期 ${it.stats.overdue}，\n" +
                                    "未来日期或无日期任务不在此范围显示）"
                            else
                                "✅ 连接成功\n「$scopeLabel」范围 ${it.groups.size} 组 / $totalItems 条\n" +
                                    "（总待办 ${it.stats.pending} / 逾期 ${it.stats.overdue}）"
                        } else "❌ 后端返回 success=false"
                    },
                    onFailure = { e ->
                        // 直出真实原因（HTTP 401/404、网络、证书等），便于排查
                        "❌ 连接失败：${e.message ?: "未知错误"}\n$endpoint"
                    }
                )
                Toast.makeText(this@ConfigActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun save(token: String, scope: String) {
        if (Prefs.getSid(this).isBlank() && token.isBlank()) {
            Toast.makeText(this, "请先在 App 内登录，或填写 report_token", Toast.LENGTH_SHORT).show()
            return
        }
        kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
            Prefs.setToken(this@ConfigActivity, appWidgetId, token)
            Prefs.setScope(this@ConfigActivity, appWidgetId, scope)
            RefreshWorker.enqueue(this@ConfigActivity)
            WidgetRepo.refresh(this@ConfigActivity, appWidgetId)
            // updateAll 必须在主线程（Glance 组合需要主线程推进）
            withContext(Dispatchers.Main) {
                TodoAppWidget().updateAll(this@ConfigActivity)
                val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                setResult(RESULT_OK, result)
                finish()
            }
        }
    }
}

@Composable
private fun ConfigScreen(
    initialToken: String,
    initialScope: String,
    baseUrl: String,
    sid: String,
    onTest: (String) -> Unit,
    onSave: (String, String) -> Unit
) {
    val context = LocalContext.current
    var token by remember { mutableStateOf(initialToken) }
    var scope by remember { mutableStateOf(initialScope) }
    val loggedIn = sid.isNotBlank()
    val scopes = listOf(
        "cur" to "今日 + 逾期（推荐）",
        "today" to "仅今天到期",
        "overdue" to "仅逾期",
        "all" to "全部未完成"
    )

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("配置待办小组件", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text("服务器：$baseUrl", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))

        if (loggedIn) {
            Text(
                "✅ 当前已登录：小组件直接使用 App 的登录会话（sid）拉取数据，无需填写 report_token。",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32)
            )
            Spacer(Modifier.height(12.dp))
            Text("登录会话 sid", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sid,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("sid", sid))
                    Toast.makeText(context, "sid 已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            }
        } else {
            Text(
                "⚠️ 当前未在 App 内登录，小组件无法使用登录会话。请在下方填写 report_token（从网页「个人设置→免密 Token」复制待办模块的 token）；或先返回 App 登录后再配置，即可免填 token。",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("report_token（必填）") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("显示范围", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Column {
            scopes.forEach { (k, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { scope = k }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = scope == k, onClick = { scope = k })
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { onTest(if (loggedIn) "" else token) }) { Text("测试连接") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onSave(if (loggedIn) "" else token, scope) }) { Text("保存") }
        }
    }
}
