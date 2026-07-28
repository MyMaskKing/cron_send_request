/**
 * 迁移执行器：按文件名编号顺序执行 migrations/*.sql
 * - 与 wrangler 的迁移风格保持一致：注释独立成行、CREATE 用 IF NOT EXISTS、INSERT 用 OR IGNORE
 * - 已跑过的迁移记录在 _migrations 表，避免重复执行
 * - 单个 SQL 文件内部按 ";" 拆语句依次执行（D1 控制台风格），可重跑
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const MIGRATIONS_TABLE_SQL = `
CREATE TABLE IF NOT EXISTS _migrations (
  name       TEXT PRIMARY KEY,
  applied_at TEXT NOT NULL DEFAULT (datetime('now'))
);
`;

/**
 * 拆分 SQL：忽略以 -- 开头的整行注释，按 ";" 分句
 * 简易实现——项目约定注释独立成行、语句内不含分号字符串常量，够用
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
 * 执行迁移
 * @param {D1Database|import('./d1-shim.mjs').D1DatabaseShim} db - D1 binding 或 shim
 * @param {string} dir - migrations 目录绝对路径
 */
export async function runMigrations(db, dir) {
  await db.prepare(MIGRATIONS_TABLE_SQL).run();

  const files = readdirSync(dir)
    .filter(f => /^\d+_.*\.sql$/i.test(f))
    .sort();

  const appliedRows = await db.prepare('SELECT name FROM _migrations').all();
  const applied = new Set((appliedRows.results || []).map(r => r.name));

  for (const file of files) {
    if (applied.has(file)) {
      console.log(`[migrate] skip ${file} (applied)`);
      continue;
    }
    const sql = readFileSync(join(dir, file), 'utf8');
    const stmts = splitSql(sql);
    console.log(`[migrate] apply ${file} (${stmts.length} statements)`);
    for (const stmt of stmts) {
      try {
        await db.prepare(stmt).run();
      } catch (err) {
        // D1 里"列已存在""表已存在"等重复迁移错误直接忽略；保证幂等重跑
        const msg = String(err && err.message || err);
        if (/already exists|duplicate column/i.test(msg)) {
          continue;
        }
        console.error(`[migrate] error in ${file}: ${msg}`);
        console.error(`  stmt: ${stmt.slice(0, 200)}`);
        throw err;
      }
    }
    await db.prepare('INSERT INTO _migrations (name) VALUES (?)').bind(file).run();
    console.log(`[migrate] ✔ ${file}`);
  }
  console.log(`[migrate] done, ${files.length} files scanned`);
}
