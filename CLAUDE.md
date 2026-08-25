# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

基于 Cloudflare Workers 的**多用户个人生活面板 + 定时推送**服务。覆盖网站监控、基金持仓、体重记录、资产月报、待办清单（Todo Widget）五个模块，按用户配置的时间推送到企业微信 / 通用 Webhook / 邮件。

无前端框架：页面由 `src/web/` 服务端拼接 HTML 字符串；图表用 QuickChart.io 生成图片 URL（Worker 无 DOM）。

**权威事实源是 `src/` 代码**。`DEV_GUIDE.md` 是更详细的二次开发向导（架构、数据模型、模块地图），改代码前先读它；`README.md` / `DEPLOY.md` / `env.example` 含早期单文件版本的遗留描述，已不完全对应当前实现。

## 常用命令

```bash
npm run dev       # wrangler dev（本地，默认连远程 D1/KV；加 --local 走纯本地 miniflare）
npm run test      # wrangler dev --local（纯本地，不碰线上资源）
npm run deploy    # wrangler deploy 到 Cloudflare
npm run tail      # 线上实时日志
npm run serve     # 纯 Node 启动 docker/server.mjs（不依赖 workerd，用 better-sqlite3 模拟 D1/KV）
```

Docker 本地部署（业务代码 `src/` 零改动）：
```bash
docker compose up -d --build      # 数据落 ./docker-data/
docker compose logs -f
```

数据库迁移：
- **Cloudflare D1**：无自动机制，按编号手动逐个执行 `wrangler d1 execute cron_db --remote --file=migrations/000N_*.sql`。
- **Docker/Node**：容器启动时自动按编号跑 `migrations/*.sql`，已执行记录在 `_migrations` 表。

手动触发一次全量调度（调试用）：`GET /cron?key=<CRON_SECRET>`（未设 `CRON_SECRET` 时免 key）。

> 本仓库**没有自动化测试**；`npm run test` 只是本地起服务，不要把它当测试套件。

## 双运行时（重要）

同一份 `src/` 业务代码跑在两种宿主：

1. **Cloudflare Workers**（主）：`fetch` + `scheduled` 双入口，D1 + KV 绑定，`wrangler.toml` 的 `crons = ["0 * * * *"]` 每小时唤醒。
2. **纯 Node + better-sqlite3**（`docker/`）：`docker/server.mjs` 用 `d1-shim.mjs` / `kv-shim.mjs` 在 SQLite 文件上模拟 D1/KV 绑定，`http-adapter.mjs` 桥接 Node http 到 Worker `fetch`，并内置 `setInterval` 每小时直接调用 `worker.scheduled()`。

改代码必须保证两侧都能跑——不要使用仅 workerd 支持的 API 而不做兜底；不要在 `src/` 里 `import node:*`（宿主桥接在 `docker/` 内完成）。

## 架构分层

固定调用链：**`api/` → `services/` → `storage/` adapter**。

| 目录 | 职责 | 约束 |
|------|------|------|
| `src/index.js` | Worker 入口：注册路由、`fetch`/`scheduled` 调度编排、消息组装 | 路由表集中在此 |
| `src/router.js` | 轻量路由器（method + path，支持 `:param`），`json/html/error` 辅助 | |
| `src/api/*.api.js` | 请求层：解析请求、**鉴权**、调 service/storage、返回 `json()` | handler 收到 `requireAuth` 的 401 `Response` 必须 `instanceof Response` 提前 return |
| `src/services/*.service.js` | 业务层：**纯计算**，不碰存储 | `monitor.service`/`notify.service` 本身就是 IO 例外；推送文案模板集中在 `report.service.js` |
| `src/storage/` | 存储抽象：业务只通过 `getStorage(env)` 取适配器，**不写裸 SQL** | 全部 SQL 在 `d1-adapter.js`，按域分组；`mysql-adapter.js` / `redis-store.js` 是预留桩 |
| `src/auth/` | KV 会话（cookie `sid`）、`requireAuth`/`requireAdmin` 中间件、密码 hash | 会话在 KV，业务数据在 D1，不要混 |
| `src/web/` | `layout.js`（外壳 + CSS）、`pages.js`（每页一个渲染函数）、`assets.js`（页面 JS 字符串常量） | 全部是 JS 字符串 |

模块清单（与 `DEV_GUIDE.md` 相比已新增 `todo` 与 `pushLog`）：
- `monitor` 网站监控、`fund` 基金持仓、`weight` 体重记录、`asset` 资产月报、`todo` 待办清单
- `push_config` 统一推送配置、`push_logs` 推送历史
- `notify_channels` 通知渠道（wechat / webhook / email）

## 定时调度（核心设计，改前必读）

**几点/几号推送不写死在 cron 表达式里**。Worker 每小时唤醒一次，读数据库 `push_config` 表，用纯函数 `shouldRun(module, cfg, now)` 判断此刻是否到点。逻辑在 `src/services/schedule.service.js`，平台无关，Node 宿主原样复用。

- `push_config.hours` / `days` 是逗号分隔多值字符串（如 `"9,18"`）；旧列 `hour`/`day` 保留兼容，`shouldRun` 优先读多值。
- 模块类型：`monitor`/`fund`/`weight`/`todo` 按小时；`asset` 按「月中某天 + 小时」。
- `cron` 触发时传空 → `handleScheduled` 忽略时间判断，所有启用模块强制推送一次（调试用）。
- 基金净值每天 15 点刷一次缓存（`fund_nav_cache`）。

## 鉴权与超管

- `requireAuth(request, env)` 返回 session 对象或 401 `Response`；`requireAdmin` 额外校验 `role==='admin'`。
- 首个超管由 `POST /api/auth/bootstrap` + `ADMIN_BOOTSTRAP_TOKEN`（secret）创建。
- **Impersonate**：超管改写同一 KV session token，保留 `admin_id` 用于恢复，`impersonating` 标志控制顶栏黄条。
- 会话 cookie 带 `Secure`；非 HTTPS 裸 IP 部署（含局域网 Docker）登录会失败，需套 HTTPS 反代（localhost 浏览器豁免）。

## 免密公开链接（无需登录，靠长期 token）

推送消息里用 `env.PUBLIC_BASE_URL`（DB 设置 > env > request.origin 三级回退，见 `config.js#resolveBaseUrl`）拼绝对 URL：

| 路径 | 用途 | token 列 |
|------|------|----------|
| `/f/:token` | 基金加仓 | `funds.share_token` |
| `/w/:token` | 体重填写 | `weight_members.share_token` |
| `/a/:token` | 资产录入 | `wallets.share_token` |
| `/wr/:token`、`/ar/:token` | 体重/资产报告查看 | `push_config.report_token` |
| Todo 系列 `/api/public/todo/:token`、`/api/public/todo-all/:token`、`/api/public/todo-widget/:token` | 待办协作与 Android 小组件 | `todos.share_token` 等 |

token 缺失时代码自动 `generateToken()` 并持久化。

## Android 配套（`android/`）

`android/` 是独立的 Gradle/Compose 项目（`xyz.a10023456.todowidget`，minSdk 26，JVM 17），实现待办清单的 **App Widget + Glance**，通过 `src/api/todo.api.js` 的公开/widget 端点与 Worker 通信。改 todo 模块的公开接口时记得核对 Android 端调用。版本号由 CI 环境变量 `VERSION_CODE` / `VERSION_NAME` 注入，本地构建回退默认值。

## 关键约定与坑

1. **存储抽象不能破**：新增数据操作 → 加在 `d1-adapter.js` 对应域分组的方法上，再在 api 层调用；禁止业务层裸 SQL。
2. **迁移 SQL 注释必须独立成行**，不要用行内 `--` 注释（D1 控制台逐条执行会出错）。文件命名 `migrations/000N_描述.sql` 递增。
3. **时区**：Worker 跑在 UTC，面向中国用户。全局偏移存 `app_settings.tz_offset`（默认 8），由超管在用户管理页设置。换算走 `time.service.js#parseOffset` + `schedule.service.js#nowCN`。**历史遗留**：`web/assets.js` 的 `COMMON_JS` 日期函数可能含硬编码 `+8*3600*1000`，改时区时前后端都要查。
4. **推送格式降级**：`config.js#effectiveFormat` 自动处理——email 把 markdown 降级为 text，wechat/webhook 把 html 降级为 text。企业微信 markdown 单条 4096 字节截断。
5. **PUBLIC_BASE_URL**：DB 设置优先，其次 `wrangler.toml` `[vars]` / 容器环境变量，最后 `request.url` origin。Docker 首次启动登录后到「系统设置」里填实际访问地址。
6. **wrangler.toml 已入库且含真实绑定 ID**（`database_id`、KV `id`），属个人基础设施信息。`README.md` 提到的 `wrangler.toml.example` 在当前仓库中并不存在，实际以 `wrangler.toml` 为准；`.gitignore` 也未忽略它，改动部署绑定前先确认。
7. **全局规约**：遵循用户 `~/.claude/CLAUDE.md` 的精简执行规约——正确性 > 最小修改 > 一致性 > 性能；修改前读最新文件；复用优先；不做无关重构；不执行 git 操作（仅可提供 commit message 建议）。

## 代码分析工具

仓库根有 `.codegraph/` 索引。定位符号、调用链、影响面时优先使用 CodeGraph MCP（`mcp__codegraph__codegraph_explore`）或 `codegraph explore` 命令，再用 LSP/grep 兜底。
