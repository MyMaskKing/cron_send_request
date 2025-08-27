# 定时访问任务 Cloudflare Worker

这是一个部署在 Cloudflare Workers 上的定时任务应用，每天早上 6 点自动访问配置的网址，并将执行结果发送到企业微信群机器人。

## 功能特性

- ⏰ **定时执行**：每天早上 6 点自动执行
- 🔗 **批量访问**：支持多个网址的批量访问
- 📊 **结果统计**：提供详细的执行结果和状态码
- 🤖 **多种通知**：支持微信机器人、通用Webhook、邮件等多种通知方式
- 🎨 **格式灵活**：支持文本和HTML两种返回格式
- 🚀 **手动触发**：支持手动触发执行
- ⚡ **并发控制**：智能控制并发数量，避免过载
- 🌐 **真实浏览器头**：使用真实的浏览器请求头，提高访问成功率

## 项目结构

```
cron_send_request/
├── src/
│   └── index.js          # 主要 Worker 代码
├── wrangler.toml         # Cloudflare Worker 配置
├── README.md             # 项目说明文档
└── design.md             # 设计需求文档
```

## 部署步骤

### 1. 安装 Wrangler CLI

```bash
npm install -g wrangler
```

### 2. 登录 Cloudflare

```bash
wrangler login
```

### 3. 配置环境变量

在 Cloudflare Dashboard 中为你的 Worker 设置环境变量，格式如下：

```
ACCESS_URL1 = "https://example1.com"
ACCESS_URL2 = "https://example2.com"
ACCESS_URL3 = "https://example3.com"
ACCESS_MY_SITE = "https://mysite.com"
```

### 4. 部署 Worker

```bash
wrangler deploy
```

## 配置说明

### 环境变量

#### 必需配置
- **ACCESS_***：所有以 `ACCESS_` 开头的环境变量都会被识别为需要访问的网址
- 变量名可以自定义，只要以 `ACCESS_` 开头即可
- 变量值必须是有效的 URL

#### 可选配置
- **RETURN_TYPE**：返回格式类型，可选值：`text`（纯文本）、`html`（美观的HTML报告）
- **WEBHOOK_URL**：通知Webhook地址，如果不设置则使用默认的微信机器人地址
- **NOTIFICATION_TYPE**：通知类型，可选值：`wechat`（微信机器人）、`webhook`（通用Webhook）、`email`（邮件）
- **NOTIFICATION_ENABLED**：是否启用通知，可选值：`true`、`false`，默认为 `true`

#### 超时和并发配置
- **REQUEST_TIMEOUT**：请求超时时间（毫秒），默认 `30000`（30秒）
- **CONCURRENCY_LIMIT**：并发请求数量限制，默认 `5`
- **BATCH_DELAY**：批次间延迟时间（毫秒），默认 `1000`（1秒）

### 定时设置

在 `wrangler.toml` 中配置：

```toml
[triggers]
crons = ["0 6 * * *"]  # 每天早上6点执行
```

Cron 表达式说明：
- `0` - 分钟 (0-59)
- `6` - 小时 (0-23)
- `*` - 日期 (1-31)
- `*` - 月份 (1-12)
- `*` - 星期 (0-7, 0和7都表示星期日)

### 通知配置

#### 微信机器人（默认）
机器人 Webhook URL 已在代码中配置：
```
https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=d379c8ce-501f-4481-89e9-f35e2b2debee
```

#### 通用Webhook
设置 `NOTIFICATION_TYPE=webhook` 和 `WEBHOOK_URL=你的webhook地址` 即可使用通用Webhook通知。

#### 邮件通知
设置 `NOTIFICATION_TYPE=email` 即可启用邮件通知功能（需要额外配置邮件服务）。

#### 禁用通知
设置 `NOTIFICATION_ENABLED=false` 可以完全禁用通知功能。

## 使用方法

### 自动执行

Worker 会在每天早上 6 点自动执行，无需手动干预。

### 手动触发

访问以下端点可以手动触发执行：

```
https://your-worker.your-subdomain.workers.dev/manual
```

### 查看项目信息

访问根路径可以查看项目信息：

```
https://your-worker.your-subdomain.workers.dev/
```

## API 端点

| 端点 | 方法 | 描述 |
|------|------|------|
| `/` | GET | 项目信息和可用端点 |
| `/manual` | GET | 手动触发执行任务 |
| `/cron` | GET | 定时任务执行（内部调用） |

## 执行结果格式

发送到微信群的消息格式示例：

```
🌅 定时任务执行报告 (2024/1/1 06:00:00)

📊 执行统计：总计 3 个，成功 2 个，失败 1 个

1. ✅ ACCESS_URL1
   URL: https://example1.com
   状态: 200 OK
   响应时间: 150ms
   时间: 2024-01-01T06:00:00.000Z

2. ✅ ACCESS_URL2
   URL: https://example2.com
   状态: 200 OK
   响应时间: 200ms
   时间: 2024-01-01T06:00:00.000Z

3. ❌ ACCESS_URL3
   URL: https://example3.com
   状态: ERROR
   响应时间: 5000ms
   时间: 2024-01-01T06:00:00.000Z
```

## 技术特性

- **并发控制**：限制同时访问的 URL 数量为 5 个，避免过载
- **错误处理**：完善的错误处理和异常捕获
- **响应时间统计**：记录每个 URL 的访问响应时间
- **状态码记录**：记录 HTTP 状态码和状态文本
- **时间戳记录**：记录每次访问的精确时间
- **真实浏览器头**：使用完整的浏览器请求头，提高访问成功率
- **格式灵活**：支持纯文本和HTML两种返回格式
- **通知扩展**：支持多种通知方式，易于集成到现有系统

## 注意事项

1. **环境变量**：确保在 Cloudflare Dashboard 中正确设置环境变量
2. **URL 格式**：环境变量值必须是完整的 URL（包含 http:// 或 https://）
3. **访问频率**：Worker 会控制访问频率，避免对目标网站造成压力
4. **错误处理**：网络错误和 HTTP 错误都会被记录并报告
5. **消息长度**：企业微信机器人有消息长度限制，如果结果过长可能需要分段发送

## 故障排除

### 常见问题

1. **Worker 不执行**
   - 检查 `wrangler.toml` 中的 cron 配置
   - 确认 Worker 已成功部署

2. **环境变量不生效**
   - 在 Cloudflare Dashboard 中重新设置环境变量
   - 确保变量名以 `ACCESS_` 开头

3. **微信群收不到消息**
   - 检查机器人 Webhook URL 是否正确
   - 确认机器人没有被禁用

4. **访问失败**
   - 检查目标 URL 是否可访问
   - 确认网络连接正常

## 开发说明

- 代码总行数：约 300 行，符合不超过 600 行的要求
- 使用模块化设计，功能分离清晰
- 包含完整的错误处理和日志记录
- 支持并发访问和频率控制

## 许可证

MIT License
