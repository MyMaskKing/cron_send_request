# 本地 Docker 部署

用 **better-sqlite3 在纯 Node 里模拟 D1 & KV**，不依赖 Cloudflare Workers 运行时。项目建设在 `docker/` 目录下，业务代码 `src/` 一行不动。

- 内存占用：**~60-90MB**（常驻，无 Miniflare/workerd 子进程）
- 镜像大小：**~120MB**（node:20-alpine 基础）
- 冷启动：**200-500ms**

## 快速开始

```bash
# 首次构建 + 启动
docker compose up -d --build

# 查看日志
docker compose logs -f
```

打开 `http://localhost:8787`：
1. 首次访问会跳转 `setup` 页 → 走 `/api/auth/bootstrap` 建超管
2. 登录后进「系统设置」把 **PUBLIC_BASE_URL** 填成你实际访问的地址（如 `http://192.168.1.10:8787`），推送消息里的免密链接才能拼对

## 数据持久化

容器把 `/data` 挂到宿主机 `./docker-data/`：
- `docker-data/d1.sqlite`  D1 数据（用户、基金、体重、资产、推送配置、待办）
- `docker-data/kv.sqlite`  KV 数据（会话、分享 token、基金净值缓存）

**备份就把 `docker-data/` 打包**；恢复解压回原位重启即可。

## 环境变量

`docker-compose.yml` 里可配置：

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | `8787` | 容器内监听端口 |
| `HOST` | `0.0.0.0` | 监听地址 |
| `DATA_DIR` | `/data` | 数据落盘目录 |
| `STORAGE_DRIVER` | `d1` | 存储驱动，固定 d1 |
| `CRON_SECRET` | 空 | `/cron?key=` 校验值，留空则免 key |
| `PUBLIC_BASE_URL` | 空 | **优先用数据库设置**；仅当未设置且请求上下文缺失时兜底 |

## 定时推送

容器内内置 `setInterval` 每小时整点触发 `worker.scheduled()`，不走 HTTP，直接调业务逻辑，功能等价 Cloudflare 平台的 `crons = ["0 * * * *"]`。到点判断由数据库 `push_config` 表决定（同源代码逻辑）。

手动全量触发（调试用）：
```bash
curl "http://localhost:8787/cron?key=<CRON_SECRET>"   # 未设 CRON_SECRET 则去掉 ?key=
```

## 迁移

首次启动会自动按编号顺序执行 `migrations/*.sql`，已跑过的记录在 `_migrations` 表里，重启不会重复执行。

新增迁移直接在 `migrations/` 建 `00NN_描述.sql`，重启容器即生效。

## 从 Cloudflare D1 导入线上数据（可选）

`migrate.mjs` **只建表不搬数据**。如果你要把线上 Cloudflare D1 的现有数据搬进本地容器：

1. **在本机装了 wrangler 的地方**导出 dump：
   ```bash
   wrangler d1 export cron_db --remote --output=./docker-data/dump.sql
   ```

2. **确保 `docker-data/` 是空的**（`docker compose down && rm -rf docker-data/`，再把 `dump.sql` 放回去）—— 导入必须在空库上跑

3. 打开 `docker/server.mjs`，把 **"从 Cloudflare 迁移数据"** 那段的四行注释去掉：
   ```js
   import { importD1Dump } from './import.mjs';
   const imported = await importD1Dump(db, join(DATA_DIR, 'dump.sql'), join(ROOT, 'migrations'));
   if (imported) console.log('[import] D1 数据已导入，后续迁移将全部跳过');
   ```

4. `docker compose up -d --build`，观察日志出现 `[import] 完成：成功 N / ...`

5. **导入成功后把那段注释加回去**，避免每次启动都重导

> KV（会话 / 分享 token / 基金净值缓存）不建议搬：会话过期就没意义，基金净值下次调度自然刷新。全新起就行。

## 反向代理 / HTTPS

Session cookie 目前带 `Secure` 标志。
- `http://localhost:8787` 浏览器豁免（能存 cookie）
- 直连 `http://192.168.x.x:8787` 或裸 IP：cookie 无法保存，登录会失败 → 请套 Nginx / Caddy 加 HTTPS，再把 `PUBLIC_BASE_URL` 设成 `https://...`

## 常见问题

**Q：想清空数据重来？**
`docker compose down && rm -rf docker-data/ && docker compose up -d`

**Q：升级代码？**
拉取新代码后 `docker compose up -d --build`，`docker-data/` 保留；新增迁移会自动执行。

**Q：手动进容器？**
`docker compose exec cron-day-report sh`

## 反向代理 / HTTPS

Session cookie 目前带 `Secure` 标志。
- `http://localhost:8787` 浏览器豁免（能存 cookie）
- 直连 `http://192.168.x.x:8787` 或裸 IP：cookie 无法保存，登录会失败 → 请套 Nginx / Caddy 加 HTTPS，再把 `PUBLIC_BASE_URL` 设成 `https://...`

## 常见问题

**Q：想清空数据重来？**
`docker compose down && rm -rf docker-data/ && docker compose up -d`

**Q：升级代码？**
拉取新代码后 `docker compose up -d --build`，`docker-data/` 保留；新增迁移会自动执行。

**Q：手动进容器？**
`docker compose exec cron-day-report sh`
