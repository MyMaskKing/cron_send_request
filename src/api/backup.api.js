/**
 * 数据全量备份与恢复（仅超管）
 * - GET  /api/admin/backup/export  下载全量业务数据 JSON（含净值缓存，不含运行日志/登录会话）
 * - POST /api/admin/backup/import  上传备份 JSON，全量覆盖当前库（单库内分块事务）
 * 备份文件可在 Cloudflare D1 与多个 Docker（better-sqlite3）实例间互导。
 */

import { json, error } from '../router.js';
import { getStorage } from '../storage/adapter.js';
import { requireAdmin } from '../auth/middleware.js';

const BACKUP_FORMAT = 'cron-day-report-backup';

/**
 * GET /api/admin/backup/export  导出全量备份文件
 */
async function exportBackup({ request, env }) {
  const auth = await requireAdmin(request, env);
  if (auth instanceof Response) return auth;

  const storage = getStorage(env);
  if (!storage.backup || !storage.backup.dumpTables) {
    return error('当前存储驱动不支持数据导出', 400);
  }

  const tables = await storage.backup.dumpTables();
  const payload = {
    format: BACKUP_FORMAT,
    version: 1,
    exported_at: new Date().toISOString(),
    tables
  };
  const date = new Date().toISOString().slice(0, 10); // YYYY-MM-DD（UTC）
  return new Response(JSON.stringify(payload), {
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Disposition': `attachment; filename="backup-${date}.json"`
    }
  });
}

/**
 * POST /api/admin/backup/import  全量覆盖导入
 * body: 备份文件解析后的对象（{ format, version, tables }）
 */
async function importBackup({ request, env }) {
  const auth = await requireAdmin(request, env);
  if (auth instanceof Response) return auth;

  const storage = getStorage(env);
  if (!storage.backup || !storage.backup.restoreTables) {
    return error('当前存储驱动不支持数据导入', 400);
  }

  let body;
  try {
    body = await request.json();
  } catch {
    return error('请求体不是合法 JSON，请上传备份文件', 400);
  }
  if (!body || body.format !== BACKUP_FORMAT || !body.tables || typeof body.tables !== 'object') {
    return error('文件格式不正确：不是本系统的备份文件', 400);
  }

  try {
    const counts = await storage.backup.restoreTables(body.tables);
    const total = Object.values(counts).reduce((s, n) => s + (Number(n) || 0), 0);
    return json({ success: true, message: `导入完成，共 ${total} 条记录`, counts, total });
  } catch (e) {
    return error('导入失败：' + (e && e.message ? e.message : String(e)), 500);
  }
}

export { exportBackup, importBackup };
