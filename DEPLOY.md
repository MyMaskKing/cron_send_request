# 快速部署指南

## 🚀 一键部署到 Cloudflare Workers

### 前置要求

1. **Cloudflare 账户**：如果没有，请先注册 [Cloudflare](https://cloudflare.com)
2. **Node.js**：版本 18.0.0 或更高
3. **Wrangler CLI**：Cloudflare 官方部署工具

### 步骤 1：安装 Wrangler CLI

```bash
npm install -g wrangler
```

### 步骤 2：登录 Cloudflare

```bash
wrangler login
```

这会打开浏览器，授权 Wrangler 访问你的 Cloudflare 账户。

### 步骤 3：配置环境变量

在 Cloudflare Dashboard 中设置环境变量：

1. 登录 [Cloudflare Dashboard](https://dash.cloudflare.com)
2. 选择你的账户
3. 点击 "Workers & Pages"
4. 找到你的 Worker（部署后会自动创建）
5. 点击 "Settings" → "Variables"
6. 添加环境变量，格式如下：

```
ACCESS_URL1 = https://example1.com
ACCESS_URL2 = https://example2.com
ACCESS_MY_SITE = https://mysite.com
```

### 步骤 4：部署 Worker

```bash
# 在项目目录中执行
wrangler deploy
```

部署成功后，你会看到类似这样的输出：

```
Deployed to https://cron-send-request.your-subdomain.workers.dev
```

### 步骤 5：验证部署

1. **访问根路径**：查看项目信息
   ```
   https://cron-send-request.your-subdomain.workers.dev/
   ```

2. **手动触发测试**：测试功能是否正常
   ```
   https://cron-send-request.your-subdomain.workers.dev/manual
   ```

3. **检查定时任务**：确认 cron 触发器已设置

## 🔧 配置说明

### 环境变量配置

#### 必需配置
- **ACCESS_***：必须以 `ACCESS_` 开头
- 变量名可以自定义，如：`ACCESS_URL1`、`ACCESS_MY_SITE`、`ACCESS_API_HEALTH`
- 变量值必须是完整的 URL

#### 可选配置
- **RETURN_TYPE**：返回格式，`text`（纯文本）或 `html`（美观HTML报告）
- **WEBHOOK_URL**：自定义通知地址，不设置则使用默认微信机器人
- **NOTIFICATION_TYPE**：通知类型，`wechat`、`webhook`、`email`
- **NOTIFICATION_ENABLED**：是否启用通知，`true` 或 `false`

#### 超时和并发配置
- **REQUEST_TIMEOUT**：请求超时时间（毫秒），默认 30000
- **CONCURRENCY_LIMIT**：并发请求数量限制，默认 5
- **BATCH_DELAY**：批次间延迟时间（毫秒），默认 1000

### 支持的 URL 格式

```
✅ 正确格式：
ACCESS_URL1 = https://example.com
ACCESS_URL2 = http://localhost:3000
ACCESS_API = https://api.example.com/health

❌ 错误格式：
ACCESS_URL1 = example.com          # 缺少协议
ACCESS_URL2 = https://             # 不完整的 URL
ACCESS_URL3 = ftp://example.com    # 不支持的协议
```

### 定时执行设置

默认设置为每天早上 6 点执行，如需修改：

1. 编辑 `wrangler.toml` 文件
2. 修改 cron 表达式：

```toml
[triggers]
crons = ["0 6 * * *"]  # 每天早上6点
# crons = ["0 */2 * * *"]  # 每2小时执行一次
# crons = ["0 9,18 * * *"]  # 每天上午9点和下午6点
```

## 🧪 测试和调试

### 本地测试

```bash
# 启动本地开发服务器
npm run dev

# 或者使用 wrangler
wrangler dev
```

### 查看日志

```bash
# 实时查看 Worker 日志
npm run tail

# 或者使用 wrangler
wrangler tail
```

### 常见问题排查

1. **Worker 不执行**
   - 检查 cron 配置是否正确
   - 确认环境变量已设置
   - 查看 Worker 日志

2. **环境变量不生效**
   - 重新部署 Worker
   - 检查变量名是否以 `ACCESS_` 开头
   - 确认变量值不为空

3. **微信群收不到消息**
   - 检查 Webhook URL 是否正确
   - 确认机器人没有被禁用
   - 查看发送结果日志

## 📱 企业微信机器人配置

### 获取 Webhook URL

1. 在企业微信群中添加机器人
2. 获取机器人的 Webhook URL
3. 确认 URL 格式正确

### 机器人权限

确保机器人有发送消息的权限，且没有被群管理员禁用。

## 🔄 更新和重新部署

### 代码更新后重新部署

```bash
wrangler deploy
```

### 环境变量更新

在 Cloudflare Dashboard 中修改环境变量后，无需重新部署，Worker 会自动读取新的环境变量。

### 配置更新

修改 `wrangler.toml` 后需要重新部署：

```bash
wrangler deploy
```

## 📊 监控和维护

### 查看执行状态

- 访问 `/manual` 端点手动触发执行
- 查看 Worker 日志了解执行情况
- 检查微信群中的执行报告

### 性能优化

- 调整并发数量（当前设置为 5）
- 优化访问间隔时间
- 监控响应时间和成功率

## 🆘 获取帮助

如果遇到问题：

1. 查看 Worker 日志
2. 检查环境变量配置
3. 验证 URL 可访问性
4. 确认企业微信机器人状态

## 📝 注意事项

1. **免费计划限制**：Cloudflare Workers 免费计划有执行次数限制
2. **网络超时**：Worker 有执行时间限制，避免访问响应过慢的网站
3. **消息长度**：企业微信机器人有消息长度限制
4. **访问频率**：避免过于频繁的访问，以免对目标网站造成压力
