/**
 * 本地部署宿主：用 Miniflare 加载 src/index.js（未改动一行业务代码）
 *
 * 职责：
 *  1. 启动前自动跑 migrations/ 下的 SQL（按文件名编号顺序）
 *  2. 用 Miniflare 内置 D1 (SQLite 落盘) + KV (落盘) 绑定，与 Cloudflare 平台同构
 *  3. 内置每小时定时器打 /cron?key=CRON_SECRET，等价于平台 cron
 *
 * 数据目录约定：DATA_DIR（默认 /data），容器请挂 volume 到这里
 *   ├── d1/                Miniflare D1 SQLite 数据库落盘
 *   └── kv/                Miniflare KV 落盘
 */
import { Miniflare } from 'miniflare';
import { runMigrations } from './migrate.mjs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, '..');
const SRC_DIR = join(ROOT, 'src');

const PORT = parseInt(process.env.PORT || '8787', 10);
const HOST = process.env.HOST || '0.0.0.0';
const DATA_DIR = process.env.DATA_DIR || '/data';
const D1_DIR = join(DATA_DIR, 'd1');
const KV_DIR = join(DATA_DIR, 'kv');
const CRON_SECRET = process.env.CRON_SECRET || '';
const STORAGE_DRIVER = process.env.STORAGE_DRIVER || 'd1';
const PUBLIC_BASE_URL = process.env.PUBLIC_BASE_URL || '';
const ADMIN_BOOTSTRAP_TOKEN = process.env.ADMIN_BOOTSTRAP_TOKEN || '';

// Miniflare 默认把 **/*.js 视作 CommonJS，本项目 src/**/*.js 全是 ESM，
// 自定义规则前置覆盖；modulesRoot 用于让相对导入以 src/ 为根解析
const mf = new Miniflare({
  modules: true,
  modulesRoot: SRC_DIR,
  scriptPath: join(SRC_DIR, 'index.js'),
  modulesRules: [
    { type: 'ESModule', include: ['**/*.js', '**/*.mjs'] }
  ],
  compatibilityDate: '2024-01-01',
  d1Databases: { DB: 'cron_db' },
  kvNamespaces: ['KV'],
  d1Persist: D1_DIR,
  kvPersist: KV_DIR,
  bindings: {
    STORAGE_DRIVER,
    ...(PUBLIC_BASE_URL ? { PUBLIC_BASE_URL } : {}),
    ...(CRON_SECRET ? { CRON_SECRET } : {}),
    ...(ADMIN_BOOTSTRAP_TOKEN ? { ADMIN_BOOTSTRAP_TOKEN } : {})
  },
  host: HOST,
  port: PORT
});

// ==================== 迁移 ====================
// Miniflare 的 D1 底层就是 SQLite，直接拿 binding 用 prepare 执行 migrations/*.sql
{
  const db = await mf.getD1Database('DB');

  // ---------- 从 Cloudflare 迁移数据（可选，默认注释关闭）----------
  // 使用步骤见 docker/import.mjs 头部注释；只在空库首次启动时启用一次
  //
  // import { importD1Dump } from './import.mjs';
  // const imported = await importD1Dump(db, join(DATA_DIR, 'dump.sql'), join(ROOT, 'migrations'));
  // if (imported) console.log('[import] D1 数据已导入，后续迁移将全部跳过');
  // ----------------------------------------------------------------

  await runMigrations(db, join(ROOT, 'migrations'));
}

// ==================== 内置 cron 触发 ====================
// 每小时打一次 /cron?key=<CRON_SECRET>；容器内自打自收，等价于 Cloudflare 平台 cron
const CRON_INTERVAL_MS = 60 * 60 * 1000;
let cronTimer = null;

async function triggerCron() {
  try {
    const url = new URL(`http://127.0.0.1:${PORT}/cron`);
    if (CRON_SECRET) url.searchParams.set('key', CRON_SECRET);
    const res = await mf.dispatchFetch(url.toString(), { method: 'GET' });
    const text = await res.text();
    console.log(`[cron] ${new Date().toISOString()} status=${res.status} ${text.slice(0, 200)}`);
  } catch (err) {
    console.error('[cron] trigger failed:', err.message);
  }
}

function scheduleCron() {
  // 与 Cloudflare "0 * * * *" 对齐：整点触发。启动时先等到下一个整点，然后每小时一次。
  const now = new Date();
  const msToNextHour = (60 - now.getMinutes()) * 60 * 1000 - now.getSeconds() * 1000 - now.getMilliseconds();
  console.log(`[cron] next fire in ${Math.round(msToNextHour / 1000)}s`);
  setTimeout(() => {
    triggerCron();
    cronTimer = setInterval(triggerCron, CRON_INTERVAL_MS);
  }, msToNextHour);
}

scheduleCron();

// ==================== 就绪 ====================
const ready = await mf.ready;
console.log(`✅ cron-day-report 本地部署已启动`);
console.log(`   监听地址: http://${HOST}:${PORT}   (对外 URL: ${ready.origin})`);
console.log(`   数据目录: ${DATA_DIR}`);
console.log(`   存储驱动: ${STORAGE_DRIVER}`);
console.log(`   首次访问 /  完成登录页 → /api/auth/bootstrap 建超管 → 登录后到「系统设置」填 PUBLIC_BASE_URL`);

// 优雅退出
async function shutdown(sig) {
  console.log(`\n[${sig}] 关闭中...`);
  if (cronTimer) clearInterval(cronTimer);
  await mf.dispose();
  process.exit(0);
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
