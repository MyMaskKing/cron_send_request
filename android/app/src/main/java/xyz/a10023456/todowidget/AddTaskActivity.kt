package xyz.a10023456.todowidget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 小组件入口的轻量新增页：仅填标题，其余字段在网页补充。 */
class AddTaskActivity : ComponentActivity() {

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setContent {
            MaterialTheme(
                colorScheme = if (isNight()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AddScreen(onSave = { title -> save(title) }, onCancel = { finish() })
                }
            }
        }
    }

    private fun isNight(): Boolean =
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun save(title: String) {
        if (title.isBlank()) {
            Toast.makeText(this, "请填写任务标题", Toast.LENGTH_SHORT).show()
            return
        }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Toast.makeText(this, "请从桌面小组件进入新增", Toast.LENGTH_LONG).show()
            return
        }
        val token = Prefs.getToken(this, widgetId)
        if (token.isBlank()) {
            Toast.makeText(this, "小组件未配置，请先配置", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val baseUrl = Prefs.getBaseUrl(this, widgetId)
        kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
            val ok = runCatching {
                ApiClient.addTask(baseUrl, token, title).success
            }.getOrDefault(false)
            if (ok) {
                WidgetRepo.refresh(this@AddTaskActivity, widgetId)
                withContext(Dispatchers.Main) {
                    TodoAppWidget().updateAll(this@AddTaskActivity)
                    Toast.makeText(this@AddTaskActivity, "已添加", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddTaskActivity, "添加失败，请检查网络", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
private fun AddScreen(onSave: (String) -> Unit, onCancel: () -> Unit) {
    var title by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("新增待办", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("任务标题") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onSave(title.trim()) },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("取消")
        }
    }
}
