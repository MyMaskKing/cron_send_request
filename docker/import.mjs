/**
 * 从 Cloudflare D1 线上数据导入本地 Miniflare（可选，默认不启用）
 *
 * 使用场景：把线上 D1 完整数据搬到本地容器
 *
 * 生成 dump.sql（在你本机装了 wrangler 的地方跑）：
 *   wrangler d1 export cron_db --remote --output=./docker-data/dump.sql
 *
 * 导入流程（对空数据目录一次性执行，导入完注释回去）：
 *   1. 停容器：docker compose down
 *   2. 清空/新建 docker-data/：数据要从零开始（避免与已有 schema 冲突）
 *   3. 把 dump.sql 放到 docker-data/dump.sql
 *   4. 打开 docker/server.mjs 中"从 Cloudflare 迁移数据"代码块的注释
 *   5. docker compose up -d --build
 *   6. 观察日志确认成功后，把注释加回去，避免每次启动都重导
 *
 * 实现要点：
 *   - dump.sql 是 wrangler 导出的完整 schema + INSERT，含 CREATE TABLE / CREATE INDEX
 *   - 需在"空库"上跑，否则表已存在会报错
 *   - 导入成功后，把 migrations/ 下所有文件名标记为已应用，
 *     避免后续 migrate.mjs 重跑 DDL 撞已存在的表/列
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs';

/**
 * 简易 SQL 拆分：忽略整行注释与空行，按 ";" 分句
 * wrangler d1 export 的输出较规整，语句内不含分号字符串常量，够用
 */
function splitSql(text) {
  const cleaned = text
    .split(/\r?\n/)
    .filter(line => !/^\s*--/.test(line))
    .join('\n');
  return cleaned
    .split(';')
    .map(s => s.trim())
    .filter(Boolean);
}

/**
 * 判断语句是否应跳过：D1 API 不支持 PRAGMA 与 BEGIN/COMMIT
 */
function shouldSkip(stmt) {
  const upper = stmt.toUpperCase();
  return upper.startsWith('PRAGMA')
      || upper.startsWith('BEGIN')
      || upper.startsWith('COMMIT')
      || upper.startsWith('END TRANSACTION');
}

/**
 * 执行导入
 * @param {D1Database} db - Miniflare 提供的 D1 binding
 * @param {string} dumpPath - dump.sql 绝对路径
 * @param {string} migrationsDir - migrations 目录，用于导入后写 _migrations 记录
 */
export async function importD1Dump(db, dumpPath, migrationsDir) {
  if (!dumpPath || !existsSync(dumpPath)) {
    console.log(`[import] 未找到 dump: ${dumpPath}，跳过导入`);
    return false;
  }

  console.log(`[import] 从 ${dumpPath} 导入 D1 数据...`);
  const sql = readFileSync(dumpPath, 'utf8');
  const stmts = splitSql(sql);
  console.log(`[import] 共 ${stmts.length} 条语句`);

  let ok = 0, skipped = 0, failed = 0;
  for (const stmt of stmts) {
    if (shouldSkip(stmt)) { skipped++; continue; }
    try {
      await db.prepare(stmt).run();
      ok++;
    } catch (err) {
      failed++;
      console.error(`[import] 失败: ${String(err.message || err).slice(0, 200)}`);
      console.error(`  stmt: ${stmt.slice(0, 200)}`);
      // 首条错误就抛，避免半灌数据
      throw err;
    }
  }
  console.log(`[import] 完成：成功 ${ok} / 跳过 ${skipped} / 失败 ${failed}`);

  // 把所有 migrations 文件名塞进 _migrations 表，标记为已应用，避免 migrate.mjs 重跑
  await db.prepare(`
    CREATE TABLE IF NOT EXISTS _migrations (
      name       TEXT PRIMARY KEY,
      applied_at TEXT NOT NULL DEFAULT (datetime('now'))
    )
  `).run();
  const files = readdirSync(migrationsDir)
    .filter(f => /^\d+_.*\.sql$/i.test(f))
    .sort();
  for (const f of files) {
    await db.prepare('INSERT OR IGNORE INTO _migrations (name) VALUES (?)').bind(f).run();
  }
  console.log(`[import] 已把 ${files.length} 个 migrations 文件名标记为已应用`);
  return true;
}
