package xyz.a10023456.todowidget

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
        if (token.isBlank()) {
            Toast.makeText(this, "请填写 report_token", Toast.LENGTH_SHORT).show()
            return
        }
        val scope = Prefs.getScope(this, appWidgetId)
        Toast.makeText(this, "测试中…", Toast.LENGTH_SHORT).show()
        kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
            val ok = runCatching {
                ApiClient.fetchWidget(AppConfig.getBaseUrl(this@ConfigActivity), token, scope).success
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@ConfigActivity,
                    if (ok) "✅ 连接成功" else "❌ 连接失败，检查 token/地址",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun save(token: String, scope: String) {
        if (token.isBlank()) {
            Toast.makeText(this, "请填写 report_token", Toast.LENGTH_SHORT).show()
            return
        }
        kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
            Prefs.setToken(this@ConfigActivity, appWidgetId, token)
            Prefs.setScope(this@ConfigActivity, appWidgetId, scope)
            RefreshWorker.enqueue(this@ConfigActivity)
            WidgetRepo.refresh(this@ConfigActivity, appWidgetId)
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
    onTest: (String) -> Unit,
    onSave: (String, String) -> Unit
) {
    var token by remember { mutableStateOf(initialToken) }
    var scope by remember { mutableStateOf(initialScope) }
    val scopes = listOf(
        "cur" to "今日 + 逾期（推荐）",
        "today" to "仅今天到期",
        "overdue" to "仅逾期",
        "all" to "全部未完成"
    )

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("配置待办小组件", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "从网页「推送设置→待办」复制 report_token；服务器地址在 App「我的→设置」修改。",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray
        )
        Spacer(Modifier.height(12.dp))
        Text("服务器：$baseUrl", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("report_token") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
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
            TextButton(onClick = { onTest(token) }) { Text("测试连接") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onSave(token, scope) }) { Text("保存") }
        }
    }
}
