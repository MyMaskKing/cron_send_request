package xyz.a10023456.todowidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 小组件配置页：透明 Activity + 底部圆角弹窗（Theme.TodoWidget.Transparent）。
 *
 * 面板外区域透出桌面与真实小组件；不透明度滑块、字号档位在拖动/点击的瞬间即写入 SP 并
 * [WidgetStateStore.publish]——配置页与小组件同进程，StateFlow 直接驱动存活 session 重组，
 * 用户在面板上就能看到小组件实时变化（无需保存）。「保存」负责 token/scope 落库、拉取数据
 * 与首次放置的 RESULT_OK；点面板外/返回 = 取消（实时预览已写入的值保留为当前效果）。
 */
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
        val opacity = Prefs.getOpacity(this, appWidgetId)
        val fontScale = Prefs.getFontScale(this, appWidgetId)
        val baseUrl = AppConfig.getBaseUrl(this)

        setContent {
            MaterialTheme(colorScheme = if (isNight()) darkColorScheme() else lightColorScheme()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        ) { finish() }
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { /* 吞噬面板内点击，避免误触 scrim 关闭 */ },
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        ConfigSheet(
                            initialToken = token,
                            initialScope = scope,
                            initialOpacity = opacity,
                            initialFontScale = fontScale,
                            baseUrl = baseUrl,
                            sid = Prefs.getSid(this),
                            onTest = { t -> testConnection(t) },
                            onOpacity = { v -> previewOpacity(v) },
                            onFontScale = { v -> previewFontScale(v) },
                            onSave = { t, s, o, f -> save(t, s, o, f) }
                        )
                    }
                }
            }
        }
    }

    /** 实时预览：写 SP 后发布到状态流，驱动桌面小组件即时重组。 */
    private fun previewOpacity(value: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        Prefs.setOpacity(this, appWidgetId, value)
        WidgetStateStore.publish(this, appWidgetId)
    }

    private fun previewFontScale(value: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        Prefs.setFontScale(this, appWidgetId, value)
        WidgetStateStore.publish(this, appWidgetId)
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

    private fun save(token: String, scope: String, opacity: Int, fontScale: Int) {
        if (Prefs.getSid(this).isBlank() && token.isBlank()) {
            Toast.makeText(this, "请先在 App 内登录，或填写 report_token", Toast.LENGTH_SHORT).show()
            return
        }
        kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
            Prefs.setToken(this@ConfigActivity, appWidgetId, token)
            Prefs.setScope(this@ConfigActivity, appWidgetId, scope)
            Prefs.setOpacity(this@ConfigActivity, appWidgetId, opacity)
            Prefs.setFontScale(this@ConfigActivity, appWidgetId, fontScale)
            WidgetStateStore.publish(this@ConfigActivity, appWidgetId)
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
private fun ConfigSheet(
    initialToken: String,
    initialScope: String,
    initialOpacity: Int,
    initialFontScale: Int,
    baseUrl: String,
    sid: String,
    onTest: (String) -> Unit,
    onOpacity: (Int) -> Unit,
    onFontScale: (Int) -> Unit,
    onSave: (String, String, Int, Int) -> Unit
) {
    var token by remember { mutableStateOf(initialToken) }
    var scope by remember { mutableStateOf(initialScope) }
    var opacity by remember { mutableStateOf(initialOpacity.toFloat()) }
    var fontScale by remember { mutableStateOf(initialFontScale) }
    val loggedIn = sid.isNotBlank()
    val scopes = listOf(
        "cur" to "今日 + 逾期（推荐）",
        "today" to "仅今天到期",
        "overdue" to "仅逾期",
        "all" to "全部未完成"
    )
    val fontLabels = listOf("小", "中", "大")
    val brand = Color(0xFFA855F7)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text("小组件设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text("服务器：$baseUrl", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))

        // 不透明度：拖动即写 SP + publish，桌面真实小组件实时变化
        Text("不透明度", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Slider(
                value = opacity,
                onValueChange = {
                    opacity = it
                    onOpacity(it.toInt())
                },
                valueRange = 0f..100f,
                steps = 9,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = brand,
                    activeTrackColor = brand
                )
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${opacity.toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(48.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        Text("字号", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fontLabels.forEachIndexed { i, label ->
                val selected = fontScale == i
                Box(
                    modifier = Modifier
                        .background(
                            color = if (selected) brand else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            fontScale = i
                            onFontScale(i)
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }
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

        if (!loggedIn) {
            Spacer(Modifier.height(12.dp))
            Text(
                "⚠️ 当前未在 App 内登录，小组件无法使用登录会话。请填写 report_token（从网页「个人设置→免密 Token」复制待办模块的 token）；或先返回 App 登录后再配置，即可免填 token。",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("report_token（必填）") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { onTest(if (loggedIn) "" else token) }) { Text("测试连接") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onSave(if (loggedIn) "" else token, scope, opacity.toInt(), fontScale) }) {
                Text("保存")
            }
        }
    }
}
